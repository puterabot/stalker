selectedImage = new ReactiveVar(null);
clickedImage = new ReactiveVar(null);

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

selectImageFromId = function (profile, imageId) {
    for (let i = 0; i < profile.images.length; i++) {
        const id_i = profile.images[i]._id._str;
        if (id_i === imageId) {
            selectedImage.set(profile.images[i]);
            return;
        }
    }
}
