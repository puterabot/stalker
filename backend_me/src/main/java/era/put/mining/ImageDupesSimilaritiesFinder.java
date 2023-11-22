package era.put.mining;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import era.put.building.ImageFileAttributes;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

public class ImageDupesSimilaritiesFinder {
    private static final Logger logger = LogManager.getLogger(ImageDupesSimilaritiesFinder.class);
    private static final int NUMBER_OF_IMAGE_DESCRIPTOR_MATCHER_CPU_AVX2_THREADS = 72;
    private static final int NUMBER_OF_IMAGE_GROUP_VALIDATOR_THREADS = 72;
    private static boolean errors;

    private static void exportFindImageDupesDescriptorsToSingleBinaryFile(String filename, FindIterable<Document> imageIterable) throws IOException {
        BufferedOutputStream writer = new BufferedOutputStream(new FileOutputStream(filename));

        imageIterable.forEach((Consumer<? super Document>) imageDocument -> {
            Object descriptorObject = imageDocument.get("af");
            if (!(descriptorObject instanceof Document)) {
                return;
            }
            Document descriptor = (Document) descriptorObject;
            Object dataObject = descriptor.get("d");
            if (!(dataObject instanceof Binary)) {
                return;
            }
            Binary data = (Binary) dataObject;
            byte[] array32 = data.getData();
            if (array32.length != 32) {
                return;
            }
            try {
                byte[] zeroByte = {0x00};
                writer.write(imageDocument.get("_id").toString().getBytes(StandardCharsets.UTF_8));
                writer.write(zeroByte);
                writer.write(array32);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.flush();
        writer.close();
    }

    private static boolean executeNativeCpuAvx2Matcher(int i, ConcurrentLinkedQueue<String> matchGroupCandidates) {
        try {
            int cpuNode = i % 2;
            String command = "/usr/bin/numactl --cpunodebind=" + cpuNode + " --membind=" + cpuNode + " ../descriptors_matcher/build/avx2 " + NUMBER_OF_IMAGE_DESCRIPTOR_MATCHER_CPU_AVX2_THREADS + " " + i;
            Process process = Runtime.getRuntime().exec(command);
            InputStream standardOutputStream = process.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(standardOutputStream));
            String line;
            while ((line = br.readLine()) != null) {
                matchGroupCandidates.add(line);
            }

            InputStream standardErrorStream = process.getErrorStream();
            BufferedReader bre = new BufferedReader(new InputStreamReader(standardErrorStream));
            while ((line = bre.readLine()) != null) {
                logger.error(line);
                throw new RuntimeException("Errors running AVX2 matcher");
            }
            process.waitFor();
        } catch (Exception e) {
            logger.error(e);
            return false;
        }
        return true;
    }

    private static void removeIsolatedNodes(Graph<String, DefaultEdge> graph) {
        var vertices = new HashSet<>(graph.vertexSet());

        for (String vertex : vertices) {
            if (graph.edgesOf(vertex).isEmpty()) {
                graph.removeVertex(vertex);
            }
        }
    }

    private static boolean candidateGroupIsValid(Set<String> group, MongoCollection<Document> imageCollection) {
        ImageFileAttributes pivotAttributes = null;
        double max = 0;
        for (String imageId: group) {
            Document filter = new Document("_id", new ObjectId(imageId));
            Document imageDocument = imageCollection.find(filter).projection(Projections.include("_id", "a")).first();
            if (pivotAttributes == null) {
                pivotAttributes = ImageFileAttributes.fromDocument((Document) imageDocument.get("a"));
            } else {
                ImageFileAttributes currentAttributes = ImageFileAttributes.fromDocument((Document)imageDocument.get("a"));
                double ratioDistance = pivotAttributes.ratioDistance(currentAttributes);
                if (max < ratioDistance) {
                    max = ratioDistance;
                }
            }
        }
        if (max > 0.1) {
            return false;
        }
        return true;
    }

    private static void classifyAllCandidateGroups(
        List<Set<String>> allCandidateSetsByDescriptorSimilarities,
        ConcurrentLinkedQueue<Set<String>> finalCandidates,
        ConcurrentLinkedQueue<Set<String>> invalidCandidates,
        MongoCollection<Document> imageCollection) {
        ThreadFactory threadFactory = Util.buildThreadFactory("ImageGroupValidator[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_IMAGE_GROUP_VALIDATOR_THREADS, threadFactory);

        for (Set<String> group: allCandidateSetsByDescriptorSimilarities) {
            executorService.submit(() -> {
                if (candidateGroupIsValid(group, imageCollection)) {
                    finalCandidates.add(group);
                } else {
                    invalidCandidates.add(group);
                }
            });
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.HOURS)) {
                logger.error("Image group candidate classifier threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }
    }

    private static void saveCorrespondencesToDatabase(List<Set<String>> candidates, MongoCollection<Document> imageCollection) {
        for (Set<String> group: candidates) {
            for (String imageId: group) {
                List<ObjectId> similarList = new ArrayList<>();
                for (String otherImageId: group) {
                    if (!imageId.equals(otherImageId)) {
                        similarList.add(new ObjectId(otherImageId));
                    }
                }
                if (similarList.size() > 0) {
                    Document filter = new Document("_id", new ObjectId(imageId));
                    Document value = new Document("$set", new BasicDBObject("afx0", similarList));
                    imageCollection.updateOne(filter, value);
                }
            }
        }
    }

    private static void processGroupCandidates(ConcurrentLinkedQueue<String> matchGroupCandidates, MongoCollection<Document> imageCollection) {
        List<Set<String>> allCandidateSetsByDescriptorSimilarities = buildCandidateSets(matchGroupCandidates);
        int[] histogram;

        histogram = buildCandidatesHistogram(allCandidateSetsByDescriptorSimilarities);
        ConcurrentLinkedQueue<Set<String>> invalidCandidates = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Set<String>> finalCandidates = new ConcurrentLinkedQueue<>();

        classifyAllCandidateGroups(allCandidateSetsByDescriptorSimilarities, finalCandidates, invalidCandidates, imageCollection);

        exportDebugFileForImageGroups("/tmp/imageGroupsValid.txt", histogram, finalCandidates.stream().toList());
        exportDebugFileForImageGroups("/tmp/imageGroupsInvalid.txt", histogram, invalidCandidates.stream().toList());

        saveCorrespondencesToDatabase(finalCandidates.stream().toList(), imageCollection);
    }

    private static int[] buildCandidatesHistogram(List<Set<String>> candidateSetsByDescriptorSimilarities) {
        int[] histogram;
        int max = 0;
        for (Set<String> candidateSet: candidateSetsByDescriptorSimilarities) {
            int n = candidateSet.size();
            if (n > max) {
                max = n;
            }
        }
        histogram = new int[max + 1];
        for (Set<String> candidateSet: candidateSetsByDescriptorSimilarities) {
            int n = candidateSet.size();
            histogram[n]++;
        }

        logger.info("Candidate groups, histogram by images per group:");
        int totalGroupCandidates = 0;
        for (int i = 0; i < max; i++) {
            if (histogram[i] > 0) {
                totalGroupCandidates += histogram[i];
                logger.info("  - h[{}]: {}", i, histogram[i]);
            }
        }
        logger.info("Total candidate groups: {}", totalGroupCandidates);
        return histogram;
    }

    private static List<Set<String>> buildCandidateSets(ConcurrentLinkedQueue<String> matchGroupCandidates) {
        List<Set<String>> candidateSetsByDescriptorSimilarities;
        HashMap<String, List<String>> imageSets = new HashMap<>();
        for (String line : matchGroupCandidates) {
            StringTokenizer parser = new StringTokenizer(line, " ");
            while (parser.hasMoreTokens()) {
                String imageId = parser.nextToken();
                if (!imageSets.containsKey(imageId)) {
                    imageSets.put(imageId, new ArrayList<>());
                }
            }
        }
        for (String line : matchGroupCandidates) {
            StringTokenizer parser = new StringTokenizer(line, " ");
            String firstIdInGroup = null;
            while (parser.hasMoreTokens()) {
                String imageId = parser.nextToken();
                if (firstIdInGroup == null) {
                    firstIdInGroup = imageId;
                } else {
                    imageSets.get(firstIdInGroup).add(imageId);
                }
            }
        }

        Graph<String, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);

        for (String key : imageSets.keySet()) {
            graph.addVertex(key);
        }

        for (String source : imageSets.keySet()) {
            for (String target : imageSets.get(source)) {
                if (source.equals(target)) {
                    break;
                }
                graph.addEdge(source, target);
            }
        }
        removeIsolatedNodes(graph);

        ConnectivityInspector<String, DefaultEdge> inspector = new ConnectivityInspector<>(graph);
        candidateSetsByDescriptorSimilarities = inspector.connectedSets();
        return candidateSetsByDescriptorSimilarities;
    }

    private static void exportDebugFileForImageGroups(String debugFilename, int[] histogram, List<Set<String>> candidateSetsByDescriptorSimilarities) {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(debugFilename));
            for (int i = histogram.length - 1; i >= 0; i--) {
                if (histogram[i] > 0) {
                    for (Set<String> candidateSet: candidateSetsByDescriptorSimilarities) {
                        if (candidateSet.size() != i) {
                            continue;
                        }
                        StringBuilder line = new StringBuilder("feh");
                        for (String id: candidateSet) {
                            char highNibble = id.charAt(22);
                            char lowNibble = id.charAt(23);
                            line.append(" " + highNibble + lowNibble + "/" + id + ".jpg");
                        }
                        writer.write(line + "\n");
                    }
                }
            }
            writer.flush();
	        writer.close();
        } catch (Exception e) {
            logger.error(e);
        }
    }

    private static void matchFindImageDupesDescriptorsUsingNativeCpuAVX2(MongoCollection<Document> imageCollection) {
        logger.info("= RUNNING FINDIMAGEDUPES DESCRIPTORS MATCHER ON NATIVE CPU AVX2 ======================");
        ThreadFactory threadFactory = Util.buildThreadFactory("FindImageDupesCpuAvx2Matcher[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_IMAGE_DESCRIPTOR_MATCHER_CPU_AVX2_THREADS, threadFactory);
        ConcurrentLinkedQueue<String> matchGroupCandidates = new ConcurrentLinkedQueue<>();
        errors = false;

        for (int i = 0; i < NUMBER_OF_IMAGE_DESCRIPTOR_MATCHER_CPU_AVX2_THREADS; i++) {
            final int pid = i;
            executorService.submit(() -> {
                if (!executeNativeCpuAvx2Matcher(pid, matchGroupCandidates)) {
                    errors = true;
                }
            });
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.HOURS)) {
                logger.error("Parent image comparator threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }

        if (errors) {
            logger.error("Native CPU AVX matchers failed. Ignoring partial results.");
        }
        logger.info("Native CPU AVX matchers results imported.");
        processGroupCandidates(matchGroupCandidates, imageCollection);

        logger.info("= FINDIMAGEDUPES DESCRIPTORS MATCHES COMPLETED =======================================");
    }

    /**
     * @param minDistance should be between 0 and 255.
     */
    public static void performMatchSearch(int minDistance) {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        ArrayList<Document> conditionsArray = new ArrayList<>();
        conditionsArray.add(new Document("x", true));
        conditionsArray.add(new Document("d", true));
        conditionsArray.add(new Document("af", new BasicDBObject("$exists", true)));
        Document filter = new Document("$and", conditionsArray);
        FindIterable<Document> imageIterable = mongoConnection.image.find(filter).projection(Projections.include("_id", "af.d"));

        try {
            String filename = "/tmp/data.raw";
            exportFindImageDupesDescriptorsToSingleBinaryFile(filename, imageIterable);
            matchFindImageDupesDescriptorsUsingNativeCpuAVX2(mongoConnection.image);
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
