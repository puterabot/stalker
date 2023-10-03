package era.put.building;

import era.put.MeBotSeleniumApp;
import java.io.File;
import java.io.PrintStream;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import era.put.base.Configuration;
import era.put.base.MongoConnection;
import era.put.base.Util;

public class PostSearcherRunnable implements Runnable {
    private static final Logger logger = LogManager.getLogger(PostSearcherRunnable.class);
    private ConcurrentLinkedQueue<PostSearchElement> searchUrls;
    private int id;
    private Configuration c;

    public PostSearcherRunnable(ConcurrentLinkedQueue<PostSearchElement> searchUrls, int id, Configuration c) {
        this.searchUrls = searchUrls;
        this.id = id;
        this.c = c;
    }

    private PrintStream createPrintStream() throws Exception {
        return new PrintStream(new File("./log/searchThread_" + id + ".log"));
    }

    private WebElement getError(WebDriver driver) {
        try {
            return driver.findElement(By.id("http-error"));
        } catch (Exception e) {
            return null;
        }
    }

    private void processList(
        WebDriver driver,
        JavascriptExecutor js,
        MongoConnection mongoConnection,
        String url,
        PrintStream out) {
        out.println("  - Importing " + url);

        String service = url.replace(c.getRootSiteUrl(), "");
        int index = service.indexOf("/");
        if (index >= 0) {
            service = service.substring(0, index - 1);
        }

        // Load all available results
        boolean clicked = false;
        do {
            Util.delay(400);
            js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
            try {
                WebElement button = driver.findElement(By.cssSelector(".listing-load-more"));
                if (button != null) {
                    button.click();
                    Util.delay(400);
                    clicked = true;
                }
            } catch (Exception e) {
                clicked = false;
            }
        } while (clicked);

        // Download results to database
        PostAnalyzer.traversePostsListInCurrentPage(driver, mongoConnection.post, service, "search", 0, out);
    }

    @Override
    public void run() {
        try {
            WebDriver driver = Util.initWebDriver(c);
            if (driver == null) {
                Util.exitProgram("Could not open web driver on deep search");
                return;
            }
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Util.login(driver, c);

            MongoConnection mongoConnection = Util.connectWithMongoDatabase();
            if (mongoConnection == null) {
                return;
            }

            PrintStream out = createPrintStream();

            PostSearchElement searchElement;
            while ((searchElement = searchUrls.poll()) != null) {
                boolean listFound = false;
                for (String url: searchElement.getUrls()) {
                    driver.get(url);
                    Util.delay(400);
                    WebElement error = getError(driver);
                    if (error != null) {
                        out.println("  - Skipping " + url);
                        continue;
                    }

                    processList(driver, js, mongoConnection, url, out);
                    listFound = true;
                }
                Document filter = new Document("_id", searchElement.getProfileId());
                Document newDocument = new Document("s", listFound);
                Document query = new Document("$set", newDocument);
                mongoConnection.profile.updateOne(filter, query);
            }
            driver.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
