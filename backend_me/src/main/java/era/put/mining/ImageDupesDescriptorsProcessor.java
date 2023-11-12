package era.put.mining;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

public class ImageDupesDescriptorsProcessor {
    private static final Logger logger = LogManager.getLogger(ImageDupesDescriptorsProcessor.class);
    private static String ME_IMAGE_DOWNLOAD_PATH;
    private static final int NUMBER_OF_DATABASE_IMPORTER_THREADS = 1; //72;
    private static final int NUMBER_OF_FINDIMAGEDUPES_THREADS = 2;

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            ME_IMAGE_DOWNLOAD_PATH = properties.getProperty("me.image.download.path");
        } catch (Exception e) {
            ME_IMAGE_DOWNLOAD_PATH = "/tmp";
        }
    }

    private static boolean processLine(String line) {
        return line.contains("data: ") && !line.contains("metadata: ");
    }

    /**
    Note that this will be valid only when using the db_dump_custom version of Berkeley db5.3 library
    provided in the project. Will fail on standard db_dump command since some escape sequences are ambiguous.
    Example: \03f is \03 followed by 'f'? or is \0 followed by "3f".
    */
    public static String convertEscapedStringToHexagesimalNibbles(String input) {
        Pattern patron = Pattern.compile("\\\\[0-9A-Fa-f]{1,2}");
        Matcher matcher = patron.matcher(input);

        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String secuenciaEscape = matcher.group();
            int valor = Integer.parseInt(secuenciaEscape.substring(1), 16);
            char caracter = (char) valor;
            matcher.appendReplacement(result, Character.toString(caracter));
        }
        matcher.appendTail(result);

        byte[] bytesArray = result.toString().getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder hexString = new StringBuilder(2 * bytesArray.length);
        for (byte b : bytesArray) {
            hexString.append(String.format("%02X", b));
        }
        return hexString.toString();
    }

    private static void processRecord(String key, String findImageDupesDescriptor, AtomicInteger totalDatabasesProcessed, MongoCollection<Document> image) {
        // Replace escape sequences in descriptor if needed
        if (findImageDupesDescriptor.length() != 64) {
            findImageDupesDescriptor = convertEscapedStringToHexagesimalNibbles(findImageDupesDescriptor);
        }

        // Find image dupes descriptors are 32 byte arrays, represented as two hexadecimal nibbles.
        if (key == null || !key.startsWith(ME_IMAGE_DOWNLOAD_PATH) || findImageDupesDescriptor.length() != 64) {
            return;
        }
        String imageFilenameRelativePath = key.substring(ME_IMAGE_DOWNLOAD_PATH.length() + 1);

        String imageId = imageFilenameRelativePath.substring(3, imageFilenameRelativePath.length() - 4);

        int n = totalDatabasesProcessed.incrementAndGet();
        if (n % 100000 == 0) {
            logger.info("Image descriptors imported: {}", n);
        }

        Document filter = new Document("_id", new ObjectId(imageId));
        byte[] binaryData = javax.xml.bind.DatatypeConverter.parseHexBinary(findImageDupesDescriptor);
        Binary binary = new Binary((byte) 0, binaryData);
        Document updateDocument = new Document("$set", new Document("af.d", binary));
        image.updateOne(filter, updateDocument);
    }

    private static void processBerkeleyDatabaseUsingDbDump(String berkeleyDatabaseFilename, AtomicInteger totalDatabasesProcessed, MongoCollection<Document> image) {
        try {
            String filename = ME_IMAGE_DOWNLOAD_PATH + "/findimagedupes/" + berkeleyDatabaseFilename;
            String command = "../custom_db_dump/build/db_dump_custom -d a " + filename;
            Process process = Runtime.getRuntime().exec(command);
            InputStream inputStream = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            int logicCount = 0; // Even numbers: read next key, odd number: read next value
            String nextKey = null;
            while ((line = reader.readLine()) != null ) {
                if (processLine(line)) {
                    int startIndex = line.indexOf("data: ") + 6;
                    String data = line.substring(startIndex);
                    if (logicCount % 2 == 0) {
                        nextKey = data;
                    } else {
                        processRecord(nextKey, data, totalDatabasesProcessed, image);
                    }
                    logicCount++;
                }
            }

            reader.close();
            process.waitFor();
            File dbFd = new File(filename);
            if (!dbFd.delete()) {
                logger.error("Can not delete " + filename);
            }
        } catch (Exception e) {
            logger.error(e);
        }
    }

    /**
    This method receives a set of image filenames, pending to process findimagedupes descriptors, and generates the
    Berkeley database file with descriptors for the given group folder. For doing this, it calls findimagedupes
    command on the filesystem.

    Note that this method should be used incrementally, so not big image sets are called (this is restricted by
    operating system maximum parameters length for a command). Initial file set on a big image set should be
    computed differently (i.e. by hand). As an example, when this code was written, there was already 1.6 million
    images to compute, making up to 6500 images per group. Their processing took 2 days and was controlled manually
    by script files.
    */
    private static void importFindImageDupesDescriptorForSet(String group, Set<String> filenames, AtomicInteger totalDescriptorGroupsProcessed) {
        logger.info("Creating findimagedupes descriptors for group {}: {} images", group, filenames.size());

        try {
            totalDescriptorGroupsProcessed.incrementAndGet();
            StringBuilder fileList = new StringBuilder();
            for (String relativeFilename: filenames) {
                File fd = new File(ME_IMAGE_DOWNLOAD_PATH + "/" + relativeFilename);
                if (fd.exists()) {
                    fileList.append(relativeFilename + " ");
                } else {
                    logger.warn("Image file not found: {}", relativeFilename);
                }
            }

            String command = "/usr/bin/findimagedupes -t 99.9% -n -f ./findimagedupes/db_" + group + ".bin -R " + fileList.toString();
            String scriptFilename = ME_IMAGE_DOWNLOAD_PATH + "/findimagedupes/script_" + group + ".sh";
            BufferedWriter scriptWriter = new BufferedWriter(new FileWriter(scriptFilename));
            scriptWriter.write("#!/bin/bash\n");
            scriptWriter.write("cd " + ME_IMAGE_DOWNLOAD_PATH + "\n");
            scriptWriter.write(command + "\n");
            scriptWriter.flush();
            scriptWriter.close();

            Process p = Runtime.getRuntime().exec("/bin/bash " + scriptFilename);

            BufferedReader stdout = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            p.waitFor();
            String line;
            while ((line = stdout.readLine()) != null) {
                logger.info(line);
            }

            String[] commonErrors = {
                "Use of uninitialized value in concatenation (.) or string at /",
                "Use of uninitialized value $ENV{\"LDFLAGS\"} in string at /"
            };
            while ((line = stderr.readLine()) != null) {
                if (setContains(commonErrors, line)) {
                    continue;
                }
                logger.error(line);
            }

            File scriptFd = new File(scriptFilename);
            if (!scriptFd.delete()) {
                logger.error("Can not delete " + scriptFd.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.error(e);
        }
    }

    private static boolean setContains(String[] commonErrors, String line) {
        for (String candidate: commonErrors) {
            if (line.startsWith(candidate)) {
                return true;
            }
        }
        if (line.startsWith("Warning: skipping file: ")) {
            String damageCandidate = line.substring(24);
            logger.error("Consider to check the health of file [{}]", damageCandidate);
            // TODO: Perform skipped file verification checks: 1. file exists 2. file re-download 3. file check
        }
        return false;
    }

    private static void createBerkeleyDatabaseFilesFromImagesWithMissingFindImageDupesDescriptors(MongoCollection<Document> image) {
        // 1: Find images without descriptors and group them by image folder
        ArrayList<Document> set = new ArrayList<>();
        set.add(new Document("x", true));
        set.add(new Document("d", true));
        set.add(new Document("af", new BasicDBObject("$exists", false)));
        Document filter = new Document("$and", set);
        FindIterable<Document> imageIterable = image.find(filter);

        Map<String, Set<String>> groups = new HashMap<>();
        imageIterable.forEach((Consumer<? super Document>) imageDocument -> {
            String _id = imageDocument.get("_id").toString();
            String groupFolder = _id.substring(22, 24);
            String relativeFilename = groupFolder + "/" + _id + ".jpg";
            if (!groups.containsKey(groupFolder)) {
                Set<String> subset = new TreeSet<>();
                subset.add(relativeFilename);
                groups.put(groupFolder, subset);
            } else {
                groups.get(groupFolder).add(relativeFilename);
            }
        });

        // 2. Compute find image dupes descriptors per folder
        ThreadFactory threadFactory = Util.buildThreadFactory("FindImageDupesDescriptorCalculator[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_FINDIMAGEDUPES_THREADS, threadFactory);
        AtomicInteger totalDescriptorGroupsProcessed = new AtomicInteger(0);

        for (String group: groups.keySet()) {
            executorService.submit(() -> importFindImageDupesDescriptorForSet(group, groups.get(group), totalDescriptorGroupsProcessed));
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.HOURS)) {
                logger.error("Find image dupes descriptor import threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }
    }

    private static void importFindImagesDupesDescriptorsFromBerkeleyDatabaseFiles(File fd, MongoCollection<Document> image) {
        ThreadFactory threadFactory = Util.buildThreadFactory("FindImageDupesDescriptorDatabaseImporter[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_DATABASE_IMPORTER_THREADS, threadFactory);
        AtomicInteger totalDatabasesProcessed = new AtomicInteger(0);

        String[] children = fd.list((dir, name) -> name.contains(".bin"));
        if (children != null) {
            for (String berkeleyDatabaseFilename : children) {
                executorService.submit(() ->
                    processBerkeleyDatabaseUsingDbDump(berkeleyDatabaseFilename, totalDatabasesProcessed, image)
                );
            }
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                logger.error("Database import threads taking so long!");
            }
        } catch (InterruptedException e) {
            logger.error(e);
        }
    }

    public static void updateFindImageDupesDescriptors() {
        String descriptorsDatabaseFolder = ME_IMAGE_DOWNLOAD_PATH + "/findimagedupes";
        File fd = new File(descriptorsDatabaseFolder);
        if (!fd.exists() || !fd.isDirectory()) {
            logger.error("{} is not a directory", descriptorsDatabaseFolder);
        }
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        createBerkeleyDatabaseFilesFromImagesWithMissingFindImageDupesDescriptors(mongoConnection.image);
        importFindImagesDupesDescriptorsFromBerkeleyDatabaseFiles(fd, mongoConnection.image);
    }
}
