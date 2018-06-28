#!/bin/bash

# This setup script will prepare new OSX vms for use with vagrant.
# Prerequisites are checked and execution is aborted if not found.
# Must have already downloaded the .ISO for macos you wish to use ahead of time.
# See either developer.apple.com or the mac app store.

# Need packer to build the vm from the .iso
command -v packer > /dev/null 2>&1 || {
  echo >&2 "Packer not found."; exit 1;
}

# Prepare the Vagrant box by downloading the (massive) file, then adding it as
# a local box.
command -v VBoxManage >/dev/null 2>&1 || {
  echo >&2 "VirtualBox not found."; exit 1;
}

command -v vagrant >/dev/null 2>&1 || {
  echo >&2 "Vagrant not found."; exit 1;
}

# vmfile=./fermi-mac.box
# if [ ! -f "$vmfile" ]; then
#   echo >&2 "Fetching Summer School Virtual Machine (VM).";
#   curl -L -s
# fi

command -v vagrant status default >/dev/null 2>&1 || {
  echo >&2 "A default vm already exists in this directory. Please destroy it with vagrant destroy and run preparevm.sh again."; exit 1;
}
vagrant box add --force fermi-box "$vmfile"
vagrant up --destroy-on-error

exit 0;

