package era.put.interleaving;

import com.mongodb.MongoCursorNotFoundException;
import com.mongodb.MongoTimeoutException;
import era.put.base.MongoUtil;
import org.bson.Document;
import org.bson.types.ObjectId;
import era.put.base.MongoConnection;

import java.io.PrintStream;

import static com.mongodb.client.model.Filters.exists;

public class PostInterleaver {
    public static void linkPostsToProfiles(PrintStream out) {
        try {
            MongoConnection c = MongoUtil.connectWithMongoDatabase();
            if (c == null) {
                return;
            }

            out.println("= LINKING POSTS TO PROFILES =========================");
            int count = 0;
            for (Document post: c.post.find(exists("u", false))) {
                ObjectId postId = post.getObjectId("_id");
                tick(out, count);
                count++;

                Document i = MongoUtil.getImageFromPostId(postId, c.image);
                if (i == null) {
                    Document filter = new Document("_id", postId);
                    Document newDocument = new Document("u", false);
                    Document query = new Document("$set", newDocument);
                    c.post.updateOne(filter, query);
                    continue;
                }
                Document filter = new Document("_id", postId);
                Document newDocument = new Document("u", i.getObjectId("u"));
                Document query = new Document("$set", newDocument);
                c.post.updateOne(filter, query);
            }
            out.println("= POSTS LINKED TO PROFILES =========================");
        } catch (MongoCursorNotFoundException | MongoTimeoutException e) {
            out.println("ERROR: linking posts to profiles");
            e.printStackTrace();
        }
    }

    private static void tick(PrintStream out, int count) {
        if (count % 100 == 0) {
            out.print(".");
            if (count % 1000 == 0) {
                out.print(" ");
                if (count % 10000 == 0) {
                    out.print("" + count + "\n");
                }
            }
        }
    }
}
