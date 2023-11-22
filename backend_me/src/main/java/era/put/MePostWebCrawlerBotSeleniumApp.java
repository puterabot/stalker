package era.put;

// Java
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
import era.put.base.Util;
import era.put.building.ParallelWorksetBuilders;
import era.put.building.PostAnalyzerRunnable;
import era.put.building.PostComputeElement;

public class MePostWebCrawlerBotSeleniumApp {
    private static final Logger logger = LogManager.getLogger(MePostWebCrawlerBotSeleniumApp.class);

    private static final int NUMBER_OF_LIST_THREADS = 1; // 32;

    private static boolean RUN_FOREVER;
    private static int POST_LIST_NUMBER_OF_PROCESSES;
    private static int POST_LIST_PROCESS_ID;

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
            POST_LIST_NUMBER_OF_PROCESSES = Integer.parseInt(properties.getProperty("web.crawler.post.listing.downloader.total.processes"));
            POST_LIST_PROCESS_ID = Integer.parseInt(properties.getProperty("web.crawler.post.listing.downloader.process.id"));
        } catch (Exception e) {
            logger.warn(e);
            RUN_FOREVER = false;
            POST_LIST_NUMBER_OF_PROCESSES = 1;
            POST_LIST_PROCESS_ID = 0;
        }
    }

    /**
     * This method traverses the m.e. pages from the regions listed on configuration.
     * Situation before:
     * - Collection post in database is empty or containing only old posts.
     * Situation after:
     * - New posts are added to post collection, with p (processed) flag undefined, for further
     *   information to be downloaded later.
     */
    private static void processPostListings(Configuration c) throws InterruptedException {
        List<Thread> threads;// 1. List profiles
        ConcurrentLinkedQueue<PostComputeElement> availableListComputeElements;
        availableListComputeElements = ParallelWorksetBuilders.buildListingComputeSet(new ConfigurationColombia(), POST_LIST_NUMBER_OF_PROCESSES, POST_LIST_PROCESS_ID);

        // Create and launch listing threads
        threads = new ArrayList<>();
        for (int i = 0; i < NUMBER_OF_LIST_THREADS; i++) {
            Thread t = new Thread(new PostAnalyzerRunnable(availableListComputeElements, c, i));
            t.setName("PostListDownloader[" + i + "]");
            threads.add(t);
            t.start();
        }

        // Wait for threads to end
        for (Thread t: threads) {
            t.join();
        }

        System.out.println("Post listings downloaded, timestamp: " + new Date());
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
            // 1. Open
            Date startDate = new Date();
            logger.info("Application started, timestamp: {}", startDate);
            Configuration c = new ConfigurationColombia();

            // 2. Web crawler loop
            processPostListings(c);

            // 3. Close
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
