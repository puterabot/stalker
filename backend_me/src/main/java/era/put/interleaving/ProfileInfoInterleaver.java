package era.put.interleaving;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoCursorNotFoundException;
import com.mongodb.MongoTimeoutException;
import org.bson.Document;
import org.bson.types.ObjectId;
import era.put.base.MongoConnection;
import era.put.base.Util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;

public class ProfileInfoInterleaver {
    public static void createExtendedProfileInfo(PrintStream out) {
        try {
            MongoConnection c = Util.connectWithMongoDatabase();
            if (c == null) {
                return;
            }

            System.out.println("= POPULATING EXTENDED PROFILE INFO =========================");
            System.out.println("Populating start timestamp: " + new Date());

            int count = 0;
            for (Document u: c.profile.find().sort(new BasicDBObject("p", 1))) {
                //
                tick(count);
                count++;

                //
                Document filter = new Document("u", u.getObjectId("_id"));
                int numPosts = 0;
                Date firstPostDate = null;
                Date lastPostDate = null;
                ArrayList<ObjectId> postIdArray = new ArrayList<>();
                ArrayList<String> postUrlArray = new ArrayList<>();
                ArrayList<String> locationArray = new ArrayList<>();
                String lastLocation = null;
                String lastService = null;
                for(Document p: c.post.find(filter).sort(new BasicDBObject("md", 1).append("t", 1))) {
                    lastPostDate = Util.getDateFromMdOrT(p);
                    if (firstPostDate == null) {
                        firstPostDate = lastPostDate;
                    }
                    postIdArray.add(p.getObjectId("_id"));
                    postUrlArray.add(p.getString("url"));
                    lastLocation = p.getString("c") + "/" + p.getString("r");
                    String l = p.getString("l");
                    if (l != null) {
                        lastLocation += "(" + l + ")";
                    }
                    locationArray.add(lastLocation);
                    lastService = p.getString("s");
                    numPosts++;
                }

                //
                int numImages = 0;
                Document conditionA = new Document("u", u.getObjectId("_id"));
                Document conditionB = new Document("x", true);
                ArrayList<Document> set = new ArrayList<>();
                set.add(conditionA);
                set.add(conditionB);
                filter = new Document("$and", set);
                ArrayList<ObjectId> imageIdArray = new ArrayList<>();
                for(Document i: c.image.find(filter)) {
                    imageIdArray.add(i.getObjectId("_id"));
                    numImages++;
                }

                //
                Document newDocument = new Document("_id", u.getObjectId("_id"))
                    .append("p", u.getString("p"))
                    .append("firstPostDate", firstPostDate)
                    .append("lastPostDate", lastPostDate)
                    .append("numPosts", numPosts)
                    .append("numImages", numImages)
                    .append("postIdArray", postIdArray)
                    .append("postUrlArray", postUrlArray)
                    .append("imageIdArray", imageIdArray)
                    .append("lastLocation", lastLocation)
                    .append("locationArray", locationArray)
                    .append("lastService", lastService);

                //
                filter = new Document("_id", u.getObjectId("_id"));
                Document prev = c.profileInfo.find(filter).first();
                if (prev == null) {
                    c.profileInfo.insertOne(newDocument);
                } else {
                    c.profileInfo.deleteOne(filter);
                    c.profileInfo.insertOne(newDocument);
                }

                //
                out.println(u.getString("p") + ", " + numPosts + ", " + numImages);
            }
            System.out.print("" + count + "\n");
            System.out.println("= EXTENDED PROFILE INFO COMPLETE =========================");
        } catch (MongoCursorNotFoundException | MongoTimeoutException e) {
            out.println("ERROR: creating extended profile info");
            e.printStackTrace();
        }
    }

    private static void tick(int count) {
        if (count % 10 == 0) {
            System.out.print(".");
            if (count % 100 == 0) {
                System.out.print(" ");
                if (count % 1000 == 0) {
                    System.out.print("" + count + "\n");
                }
            }
        }
    }
}
