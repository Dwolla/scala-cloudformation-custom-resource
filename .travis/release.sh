#!/usr/bin/env bash
set -o errexit -o nounset

USERNAME="Dwolla Bot"

commit_username=$(git log -n1 --format=format:"%an")
if [[ "$commit_username" == "$USERNAME" ]]; then
  echo "Refusing to release a commit created by this script."
  exit 0
fi

if [ "$TRAVIS_BRANCH" != "4.x" ]; then
  echo "Only the 4.x branch will be released. This branch is $TRAVIS_BRANCH."
  exit 0
fi

git config user.name "$USERNAME"
git config user.email "dev+dwolla-bot@dwolla.com"

git remote add release "https://$GH_TOKEN@github.com/Dwolla/scala-cloudformation-custom-resource.git"
git fetch release

git clean -dxf
git checkout 4.x
git branch --set-upstream-to=release/4.x

commit=$(git rev-parse HEAD)
if [ "$TRAVIS_COMMIT" != "$commit" ]; then
  echo "Checking out 4.x set HEAD to $commit, but Travis was building $TRAVIS_COMMIT, so refusing to continue."
  exit 0
fi

# Disable automatic releases while we release milestones
# sbt clean "release with-defaults"
