#!/usr/bin/env bash

CONDAPFX="/Users/vagrant/anaconda"

if [ ! -d ${CONDAPFX} ]
then

  echo "Installing Conda"

  curl -s -L https://repo.continuum.io/miniconda/Miniconda2-latest-MacOSX-x86_64.sh > anaconda.sh

  bash anaconda.sh -b -p $CONDAPFX

  rm anaconda.sh

  $CONDAPFX/bin/conda install -c conda-forge conda-build --yes

fi

if [ -O $CONDAPFX ]
then

  echo "Setting Conda Ownership"

  chown -R vagrant:staff $CONDAPFX

fi

if [ -e anaconda.sh ]
then
  rm anaconda.sh
fi


if [ ! -d "ScienceTools-conda-recipe" ]
then

  echo "Cloning ScienceTools conda recipe"

  git clone https://github.com/fermi-lat/ScienceTools-conda-recipe.git

  chown -R vagrant:staff ScienceTools-conda-recipe

  $CONDAPFX/bin/conda build -c conda-forge -c fermi_dev_externals --source .

  chown -R vagrant:staff $CONDAPFX
fi

# cd ScienceTools-conda-recipe

# $CONDAPFX/bin/conda build -c conda-forge -c fermi_dev_externals --source .

# cd ../

# chown -R vagrant:staff $CONDAPFX

# conda create --name fermi -c conda-forge -c fermi_dev_externals \
#   fermipy \
#   fermitools \
#   fermitools-data \
#   --yes

