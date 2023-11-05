computeImagePreviewSizes = function (arr) {
    let targetSize = 64;
    if (window.innerWidth < 1200) {
        targetSize = 128;
    }
    for (let i = 0; i < arr.length; i++) {
        const img = arr[i];
        const dxdy = img.a.dx / img.a.dy;
        if (img.a.dx > 3 * img.a.dy) {
            img.ddx = targetSize;
            img.ddy = targetSize / dxdy;
        } else {
            img.ddx = targetSize * dxdy;
            img.ddy = targetSize;
        }
    }
}
