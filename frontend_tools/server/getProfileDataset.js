const path = 'http://10.243.0.2/me/';

const getLocalFilename = function(_id) {
    const n = _id._str.length;
    const last2 = _id._str.substring(n - 2, n);
    return path + '/' + last2 + '/' + _id._str + '.jpg';
}

Meteor.methods({
    getProfileDataset: function(_id) {
        //console.log('Getting data for profile',_id);
        const profile = global['globalProfile'];
        const image = global['globalImage'];
        const p = profile.findOne({_id: _id});
        if (!p) {
	    return {error: '1. profile with _id: ' + _id + ' not found'};
	}

	let result = {};
        result.images = image.find({$and: [{u: p._id}, {x: true}]}).fetch();
	for (let i = 0; i < result.images.length; i++) {
	    const img = result.images[i];
	    img.localUrl = getLocalFilename(img._id);
	}
        return result;
    }
});
