package era.put;

// Java
import era.put.base.MongoUtil;
import era.put.base.SeleniumUtil;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

// Mongo
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Logging
import org.openqa.selenium.WebDriver;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

// Application
import era.put.base.Configuration;
import era.put.base.ConfigurationColombia;
import era.put.base.MongoConnection;
import era.put.base.Util;
import era.put.building.ParallelWorksetBuilders;
import era.put.building.ProfileAnalyzerRunnable;
import era.put.building.PostSearchElement;
import era.put.building.PostSearcherRunnable;
public class MeProfileWebCrawlerBotSeleniumApp {
    private static final Logger logger = LogManager.getLogger(MeProfileWebCrawlerBotSeleniumApp.class);

    private static final int NUMBER_OF_SEARCH_THREADS = 1; // 48;
    private static final int NUMBER_OF_PROFILE_THREADS = 1; // 24;

    private static boolean RUN_FOREVER;

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            RUN_FOREVER = Boolean.parseBoolean(properties.getProperty("web.crawler.forever.and.beyond"));
        } catch (Exception e) {
            logger.warn(e);
            RUN_FOREVER = false;
        }
    }

    private static void processNotDownloadedPosts(Configuration c) throws InterruptedException {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
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

    private static void processProfileInDepthSearch(Configuration c)
            throws InterruptedException {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
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

    public static void cleanUp() {
        for (WebDriver d : SeleniumUtil.getCurrentDrivers()) {
            SeleniumUtil.closeWebDriver(d);
        }
    }

    private static void mainSequence() throws Exception {
        // 0. Global init
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.OFF);

        do {
            Date startDate = new Date();
            logger.info("Application started, timestamp: {}", startDate);
            Configuration c = new ConfigurationColombia();

            // 1. Download new post urls from list pages and store them by id on post database collection
            processNotDownloadedPosts(c);

            // 2. Download known profiles in depth
            processProfileInDepthSearch(c); // from known profiles, get more posts
            processNotDownloadedPosts(c); // process new posts to enrich existing profiles

            // 8. Close
            Date endDate = new Date();
            logger.info("Program ended, timestamp: {}", endDate);
            Util.reportDeltaTime(startDate, endDate);
        } while (!RUN_FOREVER);
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
