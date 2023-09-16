package era.put.base;

import com.mongodb.client.MongoCollection;
import org.bson.Document;

public class MongoConnection {
    public MongoCollection<Document> post;
    public MongoCollection<Document> image;
    public MongoCollection<Document> profile;
    public MongoCollection<Document> profileInfo;
}
