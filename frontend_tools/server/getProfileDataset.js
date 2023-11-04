const getLocalFilename = function (startUrl, _id) {
    const n = _id._str.length;
    const last2 = _id._str.substring(n - 2, n);
    return startUrl + '/' + last2 + '/' + _id._str + '.jpg';
}

const profileDataset = function(_id, p) {
    const image = global['globalImage'];
    const startUrl = GLOBAL_properties['imageBaseUrl'];

    if (!GLOBAL_properties || !GLOBAL_properties['imageBaseUrl']) {
        return { error: 'Missing configuration file, property imageBaseUrl not found' };
    }

    if (!p) {
        return { error: '1. profile with _id: ' + _id + ' not found' };
    }

    let result = {_id: _id};

    // Fill images
    result.images = [];
    for (let i = 0; i < p.imageIdArray.length; i++) {
        const img = image.findOne({ _id: p.imageIdArray[i] });
        result.images.push(img);
    }

    for (let i = 0; i < result.images.length; i++) {
        const img = result.images[i];
        img.localUrl = getLocalFilename(startUrl, img._id);
    }

    // Fill related profiles
    result.relatedProfiles = [];
    for (let i = 0; i < p.relatedProfilesByReplicatedImages.length; i++) {
        const pI = p.relatedProfilesByReplicatedImages[i];
        const profile = {
            p: pI
        };
        result.relatedProfiles.push(profile);
    }
    result.p = p.p;
    return result;
}

Meteor.methods({
    getProfileDatasetStr: function(phone) {
	console.log('Profile for phone: ', phone);
        const profile = global['globalProfileInfo'];
        const p = profile.findOne({p: phone});
	console.log('---------------------------------------------------------------------------');
	console.log('Profile: ', p);
	console.log('---------------------------------------------------------------------------');
        return profileDataset(p._id, p);
    },
    getProfileDataset: function (_id) {
        console.log('Getting data for profile', _id);
        const profile = global['globalProfileInfo'];
        const p = profile.findOne({ _id: _id });
        return profileDataset(_id, p);
    }
});
