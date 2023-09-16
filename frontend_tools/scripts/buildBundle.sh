#!/bin/bash
#---------------------------------------------------------------------------
export DEVEL_DIR=`pwd`
export DEPLOY_APP_NAME="badoo_release"
export RELEASE_DIR=`pwd`/../$DEPLOY_APP_NAME
export TMP_DIR=/tmp/meteor_build

rm -rf $TMP_DIR
cd $DEVEL_DIR
meteor npm install --save-exact @babel/runtime
meteor --unsafe-perm build $TMP_DIR --server http://18.223.190.237

cd $TMP_DIR
tar xfz backend_meteor.tar.gz
ls -al $TMP_DIR/backend_meteor.tar.gz

#---------------------------------------------------------------------------
rm -rf $RELEASE_DIR

mv $TMP_DIR/bundle $RELEASE_DIR

cd $RELEASE_DIR
(cd programs/server && npm install)

cat << EOF > $RELEASE_DIR/run.sh

export HOSTNAME=`hostname`
export ROOT_URL=http://18.223.190.237
export MONGO_URL=mongodb://localhost:27017/badoo
export PORT=3001
cd /var/www/$DEPLOY_APP_NAME
timedatectl set-timezone America/Bogota
nohup /usr/local/bin/node /var/www/$DEPLOY_APP_NAME/main.js &> /var/log/node_badoo.log &
disown
EOF

chmod 755  $RELEASE_DIR/run.sh

cd ..
tar cvfj /tmp/b.tar.bz2 $DEPLOY_APP_NAME
scp -P 222 /tmp/b.tar.bz2 cubestudio.co:

echo APPLICATION READY TO BE RUN!
