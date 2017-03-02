#!/bin/bash
set -ex

echo "Starting script"
cd $WORKSPACE  # move from the module directory -> workspace (blazar runs your build in the module dir)

tmpDir=$(mktemp -d)

rsync -a ./target/apidocs/ $tmpDir/

git fetch --depth 1 origin gh-pages
git fetch origin gh-pages:refs/remotes/origin/gh-pages
git config user.email 'paas+janky@hubspot.com'
git config user.name 'Janky'
git checkout -b gh-pages origin/gh-pages

rsync -a $tmpDir/ ./$PROJECT_VERSION

git add --all ./$PROJECT_VERSION

git commit -m "Update Docs $BUILD_URL"
git push origin gh-pages
git checkout $GIT_COMMIT
rm -rf $tmpDir

echo "Successfully generated and pushed new documentation to gh-pages"
