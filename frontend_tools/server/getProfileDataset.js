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
        const profile = global['globalProfileInfo'];
        const image = global['globalImage'];
        const p = profile.findOne({ _id: _id });
        if (!p) {
            return { error: '1. profile with _id: ' + _id + ' not found' };
        }

        let result = {};
	result.images = [];
	for (let i = 0; i < p.imageIdArray.length; i++ ) {
	    const img = image.findOne({_id: p.imageIdArray[i]});
	    result.images.push(img);
	}

        for (let i = 0; i < result.images.length; i++) {
            const img = result.images[i];
            img.localUrl = getLocalFilename(startUrl, img._id);
        }
        return result;
    }
});
