package era.put.building;

public class FileToolReport {
    public int totalCount;
    public int nonExistentCount;
    public int accessDeniedCount;
    public int successCount;
    public int badDataCount;
    public int xMax;
    public int yMax;
    public int lastX;
    public int lastY;

    public FileToolReport() {
        totalCount = 0;
        nonExistentCount = 0;
        accessDeniedCount = 0;
        successCount = 0;
        badDataCount = 0;
        xMax = 0;
        yMax = 0;
    }

    public void print() {
        System.out.println("Counter report:");
        System.out.println("  - Total: " + totalCount);
        System.out.println("  - Non existent: " + nonExistentCount);
        System.out.println("  - Access denied: " + accessDeniedCount);
        System.out.println("  - Bad data: " + accessDeniedCount);
        System.out.println("  - Success: " + successCount);
        System.out.println("  - Max sizes: (" + xMax + ", " + yMax + ")");
    }
}
