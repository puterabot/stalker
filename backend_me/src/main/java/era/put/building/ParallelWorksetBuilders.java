package era.put.building;

import com.mongodb.BasicDBObject;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bson.Document;
import era.put.base.Configuration;
import era.put.base.MongoConnection;

import static com.mongodb.client.model.Filters.exists;

public class ParallelWorksetBuilders {
    public static ConcurrentLinkedQueue<PostComputeElement> buildListingComputeSet(Configuration c) {
        ConcurrentLinkedQueue<PostComputeElement> availableListComputeElements = new ConcurrentLinkedQueue<>();
        for (String category: c.getServices()) {
            for (String region: c.getRegions()) {
                PostComputeElement element = new PostComputeElement();
                element.service = category;
                element.region = region;
                availableListComputeElements.add(element);
            }
        }
        return availableListComputeElements;
    }

    public static ConcurrentLinkedQueue<Integer> buildProfileComputeSet(MongoConnection mongoConnection) {
        ConcurrentLinkedQueue<Integer> availableProfileComputeElements = new ConcurrentLinkedQueue<>();
        List<Integer> linearOrder = new ArrayList<>();
        for (Document p : mongoConnection.post.find(exists("p", false))) {
            linearOrder.add(p.getInteger("i"));
        }

        // Reverse order
        for (int i = linearOrder.size() - 1; i >= 0; i--) {
            availableProfileComputeElements.add(linearOrder.get(i));
        }
        return availableProfileComputeElements;
    }

    /**
    This generates URLs for searching current profiles in-depth.
    */
    public static ConcurrentLinkedQueue<PostSearchElement> buildSearchStringsForExistingProfiles(
        MongoConnection mongoConnection,
        Configuration c) {
        ConcurrentLinkedQueue<PostSearchElement> searchUrls = new ConcurrentLinkedQueue<>();
        List<PostSearchElement> linearOrderUrls = new LinkedList<>();

        ArrayList<Document> conditionsArray = new ArrayList<>();
        conditionsArray.add(new Document("s", new BasicDBObject("$exists", false)));
        conditionsArray.add(new Document("p", new BasicDBObject("$exists", true)));
        Document filter = new Document("$and", conditionsArray);
        for (Document u: mongoConnection.profile.find(filter)) {
            String phone = u.getString("p");
            if (phone != null && !phone.isEmpty() && phone.length() >= 7) {
                List<String> urls = new ArrayList<String>();
                for (String service : c.getServices()) {
                    urls.add(c.getRootSiteUrl() + service + "/buscar-" + phone);
                }
                PostSearchElement e = PostSearchElement.builder()
                        .urls(urls)
                        .profileId(u.getObjectId("_id"))
                        .build();
                linearOrderUrls.add(e);
            }
        }

        return searchUrls;
    }
}
