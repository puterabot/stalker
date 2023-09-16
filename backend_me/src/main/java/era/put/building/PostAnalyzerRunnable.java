package era.put.building;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import era.put.MeBotSeleniumApp;
import era.put.base.Configuration;
import era.put.base.MongoConnection;
import era.put.base.Util;

import java.io.PrintStream;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PostAnalyzerRunnable implements Runnable {
    private static final Logger logger = LogManager.getLogger(MeBotSeleniumApp.class);
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
            WebDriver driver = Util.initWebDriver(c);
            Util.login(driver, c);

            MongoConnection mongoConnection = Util.connectWithMongoDatabase();
            if (mongoConnection == null) {
                return;
            }

            PrintStream out = createPrintStream();

            PostComputeElement e;
            while ((e = availableListComputeElements.poll()) != null) {
                out.println("= SERVICE: " + e.service + ", REGION: " + e.region + " =====");

                String url = "https://co.mileroticos.com/" + e.service + "/" + e.region;
                driver.get(url);
                Util.delay(5000); // TODO: Ramdomize between 5 - 10 seconds

                int i = 1;
                while (PostAnalyzer.traverseListInCurrentPageAndGoNext(driver, mongoConnection.post, e.service, e.region, i, out)) {
                    i++;
                }
            }

            out.println("= Ending list thread " + Thread.currentThread().getName() + " =====");
            out.println("End timestamp: " + new Date());
            System.out.println("  - Ending list thread " + Thread.currentThread().getName());
            driver.close();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
