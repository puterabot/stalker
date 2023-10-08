package era.put.interleaving;

import com.mongodb.MongoCursorNotFoundException;
import com.mongodb.MongoTimeoutException;
import era.put.base.MongoUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;
import era.put.base.MongoConnection;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.exists;

public class ImageInterleaver {
    private static final Logger logger = LogManager.getLogger(ImageInterleaver.class);
    private static void cleanDuplicates(Document i, ArrayList<ObjectId> p, MongoConnection c, PrintStream out) {
        List<ObjectId> newReferences = new ArrayList<>();
        for (ObjectId id: p) {
            Document filter = new Document("_id", id);
            Document post = c.post.find(filter).first();
            if (post != null) {
                newReferences.add(post.getObjectId("_id"));
                out.println("    . Adding " + id.toString());
            } else {
                out.println("    . Deleting " + id.toString());
            }
        }

        Document filter = new Document("_id", i.getObjectId("_id"));
        if (newReferences.isEmpty()) {
            c.image.deleteOne(filter);
        } else if (newReferences.size() != p.size()) {
            Document newDocument = new Document("p", newReferences);
            Document query = new Document("$set", newDocument);
            c.image.updateOne(filter, query);
        }
    }

    public static void createP0References(PrintStream out) {
        try {
            MongoConnection c = MongoUtil.connectWithMongoDatabase();
            if (c == null) {
                return;
            }

            out.println("= CREATING P0 REFERENCES =========================");

            for (Document i: c.image.find(exists("p0", false))) {
                Object genericList = i.get("p");
                if (!(genericList instanceof ArrayList)) {
                    continue;
                }
                ArrayList<Object> castedList = new ArrayList<>((ArrayList<?>) genericList);
                ArrayList<ObjectId> objectIds = new ArrayList<>();
                for (Object o: castedList) {
                    if (o instanceof ObjectId) {
                        objectIds.add((ObjectId)o);
                    }
                }
                if (objectIds.size() != 1) {
                    out.println("  - Skipping " + i.getObjectId("_id").toString() + " - it has " + objectIds.size() + " elements");
                    cleanDuplicates(i, objectIds, c, out);
                    continue;
                }
                Document newDocument = new Document("p0", objectIds.get(0));
                Document filter = new Document("_id", i.getObjectId("_id"));
                Document query = new Document("$set", newDocument);
                c.image.updateOne(filter, query);
            }
            out.println("= P0 REFERENCES CREATED =========================");
        } catch (MongoCursorNotFoundException | MongoTimeoutException e) {
            out.println("ERROR: creating extended profile info");
            logger.error(e);
        }
    }
}
