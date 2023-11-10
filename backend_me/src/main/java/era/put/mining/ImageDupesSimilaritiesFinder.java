package era.put.mining;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

public class ImageDupesSimilaritiesFinder {
    private static final Logger logger = LogManager.getLogger(ImageDupesSimilaritiesFinder.class);
    private static final int NUMBER_OF_IMAGE_DESCRIPTOR_MATCHER_CPU_AVX2_THREADS = 72;
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

    private static boolean executeNativeCpuAvx2Matcher(int n, int i, ConcurrentLinkedQueue<String> matchGroupCandidates) {
        try {
            int cpuNode = i % 2;
            String command = "/usr/bin/numactl --cpunodebind=" + cpuNode + " --membind=" + cpuNode + " ../descriptors_matcher/build/avx2 " + n + " " + i;
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

            int value = process.exitValue();
            if (value != 0) {
                logger.error("Status returned by command: {}", value);
                throw new RuntimeException("Can not execute [" + command + "] - review ssh permissions / authorized_keys on agent host");
            }
        } catch (Exception e) {
            logger.error(e);
            return false;
        }
        return true;
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
                if (!executeNativeCpuAvx2Matcher(NUMBER_OF_IMAGE_DESCRIPTOR_MATCHER_CPU_AVX2_THREADS, pid, matchGroupCandidates)) {
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

        logger.info("= FINDIMAGEDUPES DESCRIPTORS MATCHES COMPLETED =======================================");
    }

    /**
    @param minDistance should be between 0 and 255.
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
