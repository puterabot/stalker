const getLocalFilename = function (startUrl, _id) {
    const n = _id._str.length;
    const last2 = _id._str.substring(n - 2, n);
    return startUrl + '/' + last2 + '/' + _id._str + '.jpg';
}

Meteor.methods({
    getProfileDataset: function (_id) {
        //console.log('Getting data for profile',_id);
        if (!GLOBAL_properties || !GLOBAL_properties['imageBaseUrl']) {
            return { error: 'Missing configuration file, property imageBaseUrl not found' };
        }

        const startUrl = GLOBAL_properties['imageBaseUrl'];
        const profile = global['globalProfile'];
        const image = global['globalImage'];
        const p = profile.findOne({ _id: _id });
        if (!p) {
            return { error: '1. profile with _id: ' + _id + ' not found' };
        }

        let result = {};
        result.images = image.find({ $and: [{ u: p._id }, { x: true }] }).fetch();
        for (let i = 0; i < result.images.length; i++) {
            const img = result.images[i];
            img.localUrl = getLocalFilename(startUrl, img._id);
        }
        return result;
    }
});
