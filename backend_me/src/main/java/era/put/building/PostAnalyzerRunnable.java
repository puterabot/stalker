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
        try {
            MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
            if (mongoConnection == null) {
                return;
            }

            PrintStream out = createPrintStream();

            PostComputeElement e;
            while ((e = availableListComputeElements.poll()) != null) {
                out.println("= SERVICE: " + e.service + ", REGION: " + e.region + " =====");
                String url = "https://co.mileroticos.com/" + e.service + "/" + e.region;
                WebDriver webDriver = SeleniumUtil.initWebDriver(c);

                if (webDriver == null) {
                    Util.exitProgram("Can not create connection with browser.");
                }

                SeleniumUtil.login(webDriver, c);
                webDriver.get(url);
                SeleniumUtil.randomDelay(3000, 6000);
                int i = 1;
                while (PostAnalyzer.traverseListInCurrentPageAndGoNext(webDriver, mongoConnection.post, e.service, e.region, i, out)) {
                    i++;
                }
                webDriver.close();
            }

            out.println("= Ending list thread " + Thread.currentThread().getName() + " =====");
            out.println("End timestamp: " + new Date());
            System.out.println("  - Ending list thread " + Thread.currentThread().getName());
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
