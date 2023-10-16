package era.put.datafixing;

public class RegionOfInterest {
    public int x0;
    public int y0;
    public int x1;
    public int y1;

    public boolean idBigArea() {
        return x0 <= x1 && y0 <= y1 && x1 - x0 >= 32 && y1 - y0 >= 32;
    }
}
