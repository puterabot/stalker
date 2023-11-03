package era.put.building;

import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.ToString;
import org.bson.Document;

@Getter
@Setter
@Builder
@ToString
public class ImageFileAttributes implements Comparable<ImageFileAttributes> {
    private long size;
    private String shasum;
    private int dx;
    private int dy;

    public ImageFileAttributes(long size, String shasum, int dx, int dy) {
        this.size = size;
        this.shasum = shasum;
        this.dx = dx;
        this.dy = dy;
    }

    public static ImageFileAttributes fromDocument(Document source) {
        return new ImageFileAttributes(
            source.getLong("size"),
            source.getString("shasum"),
            source.getInteger("dx"),
            source.getInteger("dy"));
    }

    @Override
    public int compareTo(ImageFileAttributes other) {
        int status = this.shasum.compareTo(other.shasum);
        if (status != 0) {
            return status;
        }

        if (this.size > other.size) {
            return 1;
        } else if (this.size < other.size) {
            return -1;
        }

        if (this.dx > other.dx) {
            return 1;
        } else if (this.dx < other.dx) {
            return -1;
        }

        if (this.dy > other.dy) {
            return 1;
        } else if (this.dy < other.dy) {
            return -1;
        }

        return 0;
    }
}
