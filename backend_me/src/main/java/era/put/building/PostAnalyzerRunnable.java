package era.put.building;

import era.put.base.MongoUtil;
import era.put.base.SeleniumUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import era.put.base.Configuration;
import era.put.base.MongoConnection;
import era.put.base.Util;

import java.io.PrintStream;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PostAnalyzerRunnable implements Runnable {
    private static final Logger logger = LogManager.getLogger(PostAnalyzerRunnable.class);
    private static ConcurrentLinkedQueue<PostComputeElement> availableListComputeElements;
    private final int id;
    private final Configuration c;

    public PostAnalyzerRunnable(
        ConcurrentLinkedQueue<PostComputeElement> availableListComputeElementsParam,
        Configuration c,
        int id) {
        availableListComputeElements = availableListComputeElementsParam;
        this.id = id;
        this.c = c;
    }

    private PrintStream createPrintStream() throws Exception {
        return new PrintStream("./log/listThread_" + id + ".log");
    }

    @Override
    public void run() {
        MongoConnection mongoConnection;
        mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        PrintStream out;
        try {
            out = createPrintStream();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        PostComputeElement e;

        WebDriver webDriver = null;
        SeleniumUtil.closeWebDriver(null);
        webDriver = SeleniumUtil.initWebDriver(c);

        while ((e = availableListComputeElements.poll()) != null) {
            out.println("= SERVICE: " + e.service + ", REGION: " + e.region + " =====");
            String url = "https://co.mileroticos.com/" + e.service + "/" + e.region;

            downloadPostListPagesForSpecificServiceAndRegionUrl(url, mongoConnection, webDriver, e, out);
        }

        SeleniumUtil.closeWebDriver(webDriver);

        out.println("= Ending list thread " + Thread.currentThread().getName() + " =====");
        out.println("End timestamp: " + new Date());
        System.out.println("  - Ending list thread " + Thread.currentThread().getName());
    }

    private void downloadPostListPagesForSpecificServiceAndRegionUrl(String url, MongoConnection mongoConnection, WebDriver webDriver, PostComputeElement e, PrintStream out) {
        try {
            logger.info("= New browser instance to download post list pages ========================");
            logger.info("  . URL: [{}]", url);
            if (webDriver == null) {
                Util.exitProgram("Can not create connection with browser.");
            }

            logger.info("  . Checking there is ME connection, Cloudfire free... ");
            SeleniumUtil.login(webDriver, c);
            logger.info("  . Cloudfire check done, green to go!");

            SeleniumUtil.randomDelay(500, 3000);
            webDriver.get(url);
            SeleniumUtil.randomDelay(500, 3000);
            int i = 1;
            while (PostAnalyzer.traverseListInCurrentPageAndGoNext(webDriver, mongoConnection.post, e.service, e.region, i, out)) {
                i++;
            }
        } catch (Exception ex) {
            logger.error("Error trying to download posts for {}! - retrying", url);
            logger.error(ex);
        }
    }
}
