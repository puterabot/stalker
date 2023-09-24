package era.put;

// Java
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

// Mongo
import com.mongodb.BasicDBObject;
import com.mongodb.client.model.Filters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

// Logging
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

// Application
import era.put.base.Configuration;
import era.put.base.ConfigurationColombia;
import era.put.base.MongoConnection;
import era.put.base.Util;
import era.put.building.FileToolReport;
import era.put.building.Fixes;
import era.put.building.ImageAnalyser;
import era.put.building.ParallelWorksetBuilders;
import era.put.building.ProfileAnalyzerRunnable;
import era.put.building.PostAnalyzerRunnable;
import era.put.building.PostSearchElement;
import era.put.building.PostComputeElement;
import era.put.building.PostSearcherRunnable;
import era.put.building.RepeatedImageDetector;
import era.put.interleaving.ImageInterleaver;
import era.put.interleaving.PostInterleaver;
import era.put.interleaving.ProfileInfoInterleaver;
import era.put.mining.ImageInfo;

public class MeBotSeleniumApp {
    private static final Logger logger = LogManager.getLogger(MeBotSeleniumApp.class);

    private static final int NUMBER_OF_LIST_THREADS = 1; // 32;
    private static final int NUMBER_OF_SEARCH_THREADS = 1; // 48;
    private static final int NUMBER_OF_PROFILE_THREADS = 1; // 24;

    public static final List<WebDriver> currentDrivers = new ArrayList<>();

    private static void processNotDownloadedProfiles(Configuration c) throws InterruptedException {
        MongoConnection mongoConnection = Util.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

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
        for (Thread t: threads) {
            t.join();
        }
        System.out.println("New profiles downloaded, timestamp: " + new Date());
    }

    /**
     *
     * @param c
     * @throws InterruptedException
     */
    private static void processProfileInDepthSearch(Configuration c)
            throws InterruptedException {
        MongoConnection mongoConnection = Util.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        List<Thread> threads;
        ConcurrentLinkedQueue<PostSearchElement> searchElements = ParallelWorksetBuilders.buildSearchStringsForExistingProfiles(mongoConnection, c);

        System.out.println("NUMBER OF SEARCH URLS TO DOWNLOAD: " + searchElements.size());

        // Create and launch listing threads
        threads = new ArrayList<>();
        for (int i = 0; i < NUMBER_OF_SEARCH_THREADS; i++) {
            Thread t = new Thread(new PostSearcherRunnable(searchElements, i, c));
            t.setName("SEARCH[" + i + "]");
            threads.add(t);
            t.start();
        }

        // Wait for threads to end
        for (Thread t: threads) {
            t.join();
        }
    }

    /**
     * This method traverses the m.e. pages from the regions listed on configuration.
     * Situation before:
     * - Collection post in database is empty or containing only old posts.
     * Situation after:
     * - New posts are added to post collection, with p (processed) flag undefined, for further
     *   information to be download later.
     */
    private static void processPostListings(Configuration c) throws InterruptedException {
        List<Thread> threads;// 1. List profiles
        ConcurrentLinkedQueue<PostComputeElement> availableListComputeElements;
        availableListComputeElements = ParallelWorksetBuilders.buildListingComputeSet(new ConfigurationColombia());

        // Create and launch listing threads
        threads = new ArrayList<>();
        for (int i = 0; i < NUMBER_OF_LIST_THREADS; i++) {
            Thread t = new Thread(new PostAnalyzerRunnable(availableListComputeElements, c, i));
            t.setName("LIST[" + i + "]");
            threads.add(t);
            t.start();
        }

        // Wait for threads to end
        for (Thread t: threads) {
            t.join();
        }

        System.out.println("Post listings downloaded, timestamp: " + new Date());
    }

    private static void processImages(Configuration c) throws Exception {
        MongoConnection mongoConnection = Util.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        // Update image date if not present
        for (Document i: mongoConnection.image.find(Filters.exists("md", false))) {
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

    private static void fixDatabaseCollections() throws Exception {
        MongoConnection mongoConnection = Util.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        Fixes.fixPostCollection(mongoConnection.post);
        Fixes.fixRelationships(mongoConnection);
    }

    private static void mainSequence() throws Exception {
        // 0. Global init
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.OFF);

        System.out.println("Application started, timestamp: " + new Date());
        Configuration c = new ConfigurationColombia();

        // 1. Download new post urls from list pages and store them by id on post database collection
        //processPostListings(c);

        // 2. Download known profiles in depth
        //processProfileInDepthSearch(c);

        // 3. Download new profiles detail
        processNotDownloadedProfiles(c);

        // 4. Analise images on disk
        //processImages(c);

        // 5. Execute fixes
        //fixDatabaseCollections();

        // 6. Print some dataset trivia
        //ImageInfo.reportProfilesWithCommonImages();

        // 7. Build extended information
        //ImageInterleaver.createP0References(System.out);
        //PostInterleaver.linkPostsToProfiles(System.out);
        //ProfileInfoInterleaver.createExtendedProfileInfo(new PrintStream("./log/userStats.csv"));
        //System.out.println("Interleaving timestamp: " + new Date());

        // 8. Close
        System.out.println("Program ended, timestamp: " + new Date());
    }

    public static void panicCheck(WebDriver webDriver) {
        WebElement iconCheck = webDriver.findElement(By.id("logo"));
        if (iconCheck == null) {
            logger.error("PANIC!: RESTART SESSION - CHECK COUNTER BOT MEASURES HAS NOT BEEN TRIGGERED!");
            Util.closeWebDriver(webDriver);
            MeBotSeleniumApp.cleanUp();
            Util.exitProgram("Panic test failed.");
        }
    }

    public static void cleanUp() {
        for (WebDriver d : currentDrivers) {
            Util.closeWebDriver(d);
        }
    }

    public static void main(String[] args) {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("SIGNAL - SHUTDOWN HOOK CLOSING PROGRAM RESOURCES");
                cleanUp();
            }));

            Thread.currentThread().setName("MAIN_THREAD");

            mainSequence();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
