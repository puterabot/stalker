package era.put;

// Java
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

// Mongo
import com.mongodb.BasicDBObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

// Logging
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

// Application
import era.put.base.Configuration;
import era.put.base.ConfigurationColombia;
import era.put.base.MongoConnection;
import era.put.base.Util;
import era.put.building.*;
import era.put.interleaving.ImageInterleaver;
import era.put.interleaving.PostInterleaver;
import era.put.interleaving.ProfileInfoInterleaver;
import era.put.mining.ImageInfo;

import static com.mongodb.client.model.Filters.exists;

/**
 * TODO: Similar project to this to browse "SKOKKA"
 */

public class MeBotSeleniumApp {
    private static final Logger logger = LogManager.getLogger(MeBotSeleniumApp.class);

    private static final int NUMBER_OF_LIST_THREADS = 1; // 32;
    private static final int NUMBER_OF_PROFILE_THREADS = 1; // 24;
    private static final int NUMBER_OF_SEARCH_THREADS = 1; // 48;

    /*
    time findimagedupes -t 99.9% -f /tmp/db.bin -R . > /tmp/groups.txt
    288min (5h)
    */

    private static void processNotDownloadedProfiles(MongoConnection mongoConnection, Configuration c) throws InterruptedException {
        List<Thread> threads;
        ConcurrentLinkedQueue<Integer> availableProfileComputeElements = ParallelWorksetBuilders.buildProfileComputeSet(mongoConnection);

	    System.out.println("NUMBER OF NEW POSTS TO DOWNLOAD: " + availableProfileComputeElements.size());
	
        // Create and launch listing threads
        System.out.println("Starting " + NUMBER_OF_PROFILE_THREADS + " profile threads. Individual logs in ./log folder");
        threads = new ArrayList<>();
        for (int i = 0; i < NUMBER_OF_PROFILE_THREADS; i++) {
            Thread t = new Thread(new ProfileAnalyzerRunnable(availableProfileComputeElements, i, c));
            t.setName("PROFILE[" + i + "]");
            threads.add(t);
            t.start();
        }

        // Wait for threads to end
        System.out.println("Waiting for profile threads to end...");
        for (Thread t: threads) {
            t.join();
        }
    }

    private static void processProfileInDepthSearch(MongoConnection mongoConnection, Configuration c)
            throws InterruptedException {
        List<Thread> threads;
        ConcurrentLinkedQueue<PostSearchElement> searchElements = ParallelWorksetBuilders.buildSearchStringsForExistingProfiles(mongoConnection, c);

        System.out.println("NUMBER OF SEARCH URLS DOWNLOAD: " + searchElements.size());

        // Create and launch listing threads
        System.out.println("Starting " + NUMBER_OF_SEARCH_THREADS + " search threads. Individual logs in ./log folder");
        threads = new ArrayList<>();
        for (int i = 0; i < NUMBER_OF_SEARCH_THREADS; i++) {
            Thread t = new Thread(new PostSearcherRunnable(searchElements, i, c));
            t.setName("SEARCH[" + i + "]");
            threads.add(t);
            t.start();
        }

        // Wait for threads to end
        System.out.println("Waiting for list threads to end...");
        for (Thread t: threads) {
            t.join();
        }
        System.out.println("Threads OK");
    }

    private static void processPostListings(Configuration c) throws InterruptedException {
        List<Thread> threads;// 1. List profiles
        ConcurrentLinkedQueue<PostComputeElement> availableListComputeElements;
        availableListComputeElements = ParallelWorksetBuilders.buildListingComputeSet(new ConfigurationColombia());

        // Create and launch listing threads
        System.out.println("Starting " + NUMBER_OF_LIST_THREADS + " post list threads. Individual logs in ./log folder");
        threads = new ArrayList<>();
        for (int i = 0; i < NUMBER_OF_LIST_THREADS; i++) {
            Thread t = new Thread(new PostAnalyzerRunnable(availableListComputeElements, c, i));
            t.setName("LIST[" + i + "]");
            threads.add(t);
            t.start();
        }

        // Wait for threads to end
        System.out.println("Waiting for list threads to end...");
        for (Thread t: threads) {
            t.join();
        }
        System.out.println("Threads OK");
    }

    private static void processImages(MongoConnection mongoConnection, Configuration c) throws Exception {
        // Update image date if not present
        for (Document i: mongoConnection.image.find(exists("md", false))) {
            ImageAnalyser.updateDate(mongoConnection.image, i, c);
        }

        // Download images
        Fixes.deleteDanglingImages(mongoConnection.image);
        Fixes.downloadMissingImages(mongoConnection.image);

        // Gather information from image files
        FileToolReport fileToolReport = new FileToolReport();
        Document filter = new Document("a", new BasicDBObject("$exists", false)).append("d", true);
        for (Document i: mongoConnection.image.find(filter)) {
            ImageAnalyser.processImageFile(mongoConnection.image, i, fileToolReport, System.out);
        }
        fileToolReport.print();
        RepeatedImageDetector.groupImages(mongoConnection.image);
    }

    public static void main(String[] args) {
        try {
            // 0. Global init
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.OFF);
            System.out.println("Start timestamp: " + new Date());
            Configuration c = new ConfigurationColombia();

            // 1. Download new post urls from list pages and store them by id on post database collection
            processPostListings(c);
            System.out.println("List timestamp: " + new Date());

            // 2. Download known profiles in depth
            MongoConnection mongoConnection = Util.connectWithMongoDatabase();
            if (mongoConnection == null) {
                return;
            }
            processProfileInDepthSearch(mongoConnection, c);

            // 2. Download new profiles detail
            processNotDownloadedProfiles(mongoConnection, c);
            System.out.println("Profile timestamp: " + new Date());

            // 4. Analise images on disk
            processImages(mongoConnection, c);

            // 5. Execute fixes
            Fixes.fixPostCollection(mongoConnection.post);
            Fixes.fixRelationships(mongoConnection);

            // 6. Print some dataset trivia
            ImageInfo.reportProfilesWithCommonImages(mongoConnection.image, mongoConnection.profile);

            // 7. Build extended information
            ImageInterleaver.createP0References(mongoConnection, System.out);
            PostInterleaver.linkPostsToProfiles(mongoConnection, System.out);
            ProfileInfoInterleaver.createExtendedProfileInfo(mongoConnection, new PrintStream("./log/userStats.csv"));
            System.out.println("Interleaving timestamp: " + new Date());

            // 8. Close
            System.out.println("Fix timestamp: " + new Date());
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
