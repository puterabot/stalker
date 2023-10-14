package era.put.mining;

import era.put.base.Util;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ImageDupesDescriptorsProcessor {
    private static final Logger logger = LogManager.getLogger(ImageDupesDescriptorsProcessor.class);
    private static String ME_IMAGE_DOWNLOAD_PATH;
    private static final int NUMBER_OF_DATABASE_IMPORTER_THREADS = 2;

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

    private static void processRecord(String key, String findImageDupesDescriptor) {
        // Find image dupes descriptors are 32 byte arrays, represented as two hexadecimal nibbles.
        if (key == null || !key.startsWith(ME_IMAGE_DOWNLOAD_PATH) || findImageDupesDescriptor.length() != 64) {
            return;
        }
        String imageFilenameRelativePath = key.substring(ME_IMAGE_DOWNLOAD_PATH.length() + 1);

        String imageId = imageFilenameRelativePath.substring(3, imageFilenameRelativePath.length() - 4);

        logger.info("key[{}]: {}", imageId, findImageDupesDescriptor);
    }
    private static void processBerkeleyDatabase(String berkeleyDatabaseFilename, AtomicInteger totalDatabasesProcessed) {
        try {
            int n = totalDatabasesProcessed.incrementAndGet();
            logger.info("Processing database [{{}}]: [{}]", n, berkeleyDatabaseFilename);
            String command = "db_dump -d a " + berkeleyDatabaseFilename;
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
                        processRecord(nextKey, data);
                    }
                    logicCount++;
                }
            }

            reader.close();
            process.waitFor();
        } catch (Exception e) {
            logger.error(e);
        }
    }

    public static void updateFindImageDupesDescriptors() {
        String descriptorsDatabaseFolder = ME_IMAGE_DOWNLOAD_PATH + "/findimagedupes";
        File fd = new File(descriptorsDatabaseFolder);
        if (!fd.exists() || !fd.isDirectory()) {
            logger.error("{} is not a directory", descriptorsDatabaseFolder);
        }

        ThreadFactory threadFactory = Util.buildThreadFactory("FindImageDupesDescriptorDatabaseImporter[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_DATABASE_IMPORTER_THREADS, threadFactory);
        AtomicInteger totalDatabasesProcessed = new AtomicInteger(0);

        File[] children = fd.listFiles();
        for (File berkeleyDatabaseFile: children) {
            executorService.submit(() ->
                processBerkeleyDatabase(berkeleyDatabaseFile.getAbsolutePath(), totalDatabasesProcessed)
            );
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
}
