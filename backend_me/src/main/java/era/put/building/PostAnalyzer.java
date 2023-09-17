package era.put.building;

// Java classes
import java.io.PrintStream;
import java.util.Date;
import java.util.List;

// Mongo classes
import com.mongodb.client.MongoCollection;
import org.bson.Document;

// Selenium classes
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import era.put.base.Util;

import static com.mongodb.client.model.Filters.eq;

public class PostAnalyzer {
    /**
     * Extract post links from current page.
     */
    public static void
    traversePostsListInCurrentPage(
        WebDriver d,
        MongoCollection<Document> post,
        String category,
        String region, int pageCount,
        PrintStream out) {
        List<WebElement> l = d.findElements(By.className("thumbail"));
        //boolean orig = true;

        String msg = String.format("Page %02d : ", pageCount);
        out.print(msg);
        for (WebElement e: l) {
            WebElement a = e.findElement(By.tagName("a"));
            if (a == null) {
                continue;
            }

            // Identify location from page
            String location = null;
            WebElement place = e.findElement(By.cssSelector("small.display-block"));
            if (place != null && place.getText() != null && !place.getText().isEmpty()) {
                location = place.getText();
            }

            // Identify url from post
            String url = a.getAttribute("href");
            if (url == null) {
                continue;
            }

            // Check if post already exist at database
            Document prev;
            Integer postId = Util.extractIdFromPostUrl(url);
            if (postId == null) {
                out.println("ERROR: Unexpected post url " + url + " killing thread " + Thread.currentThread().getName());
                //System.exit(666);
                Thread.currentThread().interrupt();
            }

            prev = post.find(eq("i", postId)).first();
            if (prev == null) {
                // Insert post
                Document o = new Document();
                o.append("url", url);
                o.append("t", new Date());
                o.append("c", "co");
                o.append("s", category);
                o.append("r", region);
                o.append("l", location);
                o.append("i", postId);
                try {
                    post.insertOne(o);
                } catch (Exception ex) {
                    // Duplicate key?
                }
                out.print("*");
            } else {
                //orig = false;
                if (location != null && !location.isEmpty() && prev.get("l") == null) {
                    out.print("!");
                    Document filter = new Document().append("_id", prev.get("_id"));
                    Document newObject = new Document().append("l", location);
                    Document query = new Document().append("$set", newObject);
                    post.updateOne(filter, query);
                } else {
                    out.print(".");
                }
            }
        }
        out.print("\n");
        //return orig;
    }

    /**
     * Traverse the list of profiles on current page. If next button is available clicks it to go to
     * next page and returns true. If next button is not available returns false.
     * @param d selenium driver
     * @param post mongo collection
     * @param category mongo collection
     * @param region mongo collection
     * @param pageCount current page index
     * @param out where to send logs
     * @return if there are more results pending to process.
     */
    public static boolean
    traverseListInCurrentPageAndGoNext(
        WebDriver d,
        MongoCollection<Document> post,
        String category,
        String region,
        int pageCount,
        PrintStream out) {
        Util.delay(400);
        Util.closeDialogs(d);
        //boolean allNewInPage =
        traversePostsListInCurrentPage(d, post, category, region, pageCount, out);
        try {
            Util.scrollDownPage(d);
            Util.closeDialogs(d);
            Util.scrollDownPage(d);
            WebElement n = d.findElement(By.cssSelector("li.next"));
            if (n != null) {
                WebElement l = n.findElement(By.tagName("a"));
                if (l != null) {
                    l.click();
                    return true;
                }
            }
            return false;
        } catch (Exception x) {
            return false;
        }
    }
}
