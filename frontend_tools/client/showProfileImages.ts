import { Meteor } from 'meteor/meteor';
import { ReactiveVar } from 'meteor/reactive-var';
import { Template } from 'meteor/templating';

const profilesCache = new ReactiveVar({});

Template.showProfileImagesTemplate.helpers({
    profileData: function () {
        let cache = profilesCache.get();
        if (!cache[this.p]) {
            Meteor.call('getProfileDatasetStr', this.p, function (e, r) {
                if (!e) {
                    if (r['error']) {
                        console.log('ERROR for ', this._id);
                    } else if (!r.images) {
                        console.log('ERROR: Profile has no images');
                    } else {
                        cache[r.p] = r;
                        profilesCache.set(cache);
                        computeImagePreviewSizes(r.images);
                    }
                }
            });
        }
        let info = null;
        if (cache[this.p]) {
            info = cache[this.p];
        }
        return info;
    }
});

Template.showProfileImagesTemplate.events({
    "mouseenter .previewImage": function (e) {
        const imageId = e.target.id;
        const profilePhone = e.target.parentElement.id;
        const profile = profilesCache.get()[profilePhone];
        if (profile) {
            selectImageFromId(profile, e.target.id);
        } else {
            console.log('Phone not found');
            console.log(profilesCache.get());
        }
    }
});
