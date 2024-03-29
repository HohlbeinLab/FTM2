# Faster Temporal Median
A rewritten version of the Fast Temporal Median Filter, enabling extra features like more bit-depth support and larger-than-ram datasets, as well as being slightly faster than previous versions.

## Installation
One can get the Plugin by using the download link below, or by subscribing to the Hohlbein lab update site.
You can subscribe by going to Help > Update... > Manage Update Sites and checking the Hohlbein lab site.
It will automatically be downloaded and updated.

Current Version: 0.10.4
Download [Here](https://github.com/HohlbeinLab/FTM2/releases/latest)  
Once downloaded it can be installed by launching ImageJ > Plugins > Install... Selecting the downloaded jar and restarting ImageJ.  
The plugin should then show up in the Plugins menu under "Faster Temporal Median".  


## Purpose
This plugin takes the median over time of e.g. 50 pixels on consecutive frames, taken from 50 frames at the same coordinate, and subtracts that from the pixel in the middle, moving the window along with the pixel.  
This corrects for pixel specific noise in a sensor and allows for clean-up of super resolution data since generally little data is present per frame.  
The window should be chosen in such a way that signal occupies less than half of the window in that pixel.  

## Usage

The plugin currently only works on tif images with a bit-depth of 8, 16 or 32 bits.  
The maximum file size to be processes is not limited, images larger than ram will be written to disk.  
When processing more than one file, they all need to be of the same resolution and bit-depth.  
All selected files are concatenated in alphabetical order.  

The variables that can be set are:  
* Start - From which frame the plugin should start  
* End - Till which frame the plugin should run. When set to 0, it will process till the last frame.
* Window - The window of which the median will be taken   

* New Method - Uses a slightly newer method for determining the median which might be faster depending on your application (default: false)<br>
* Save Data - If the produced data should be saved to disk. If checked a target directory must be provided  
    Note that the program does not know the size of the files. If files are selected that do not fit in RAM
    it will have to save them.
    You can increase available RAM by going to Edit > Options > Memory & Threads  

There are three options in the Plugins > Faster Temporal Median menu.  
They all use the same back-end, but allow you to select files in different ways.  
* Select Files and Run  
* Select Folder and run  
* Use Opened Image and Run  

### Select Files and Run
With this option you can select one or multiple files from the same folder to process.  

### Select Folder and Run
With this option you can select a folder from which to run all files.  
Any subfolders or non-tif files are ignored in this.  

### Use Opened Image and Run
With this option the selected image is used for processing.  
This option will be slightly faster, as the loading of data is already done.  

## Running from a Macro
This plugin can also be run from a macro.  
An example: `run("Select Files and Run", "source=C:\C:\Users\Your_Name\your_folder\image_file.tif target=your_folder start=1 end=0 window=50 save_data=0")`  
All keywords must be provided in the format: `keyword=value`, seperated by spaces.  
The keywords available are:  
* source - The path to the folder from which to process files (optionally surround with " if spaces are present in the filename)
* target - The path to which the processed data is to be saved (optionally surround with " if spaces are present in the filename)
* file - The path to the file which to process (optionally surround with " if spaces are present in the filename)
* start - From which frame the plugin should start (default: 1)
* end - Till which frame the plugin should run. When set to 0, it will process all frames (default: all frames)
* window - The window of which the median will be taken (default: 50)
* save_data - If the produced data should be saved to disk. If set the target keyword must also be included. (default: 0)
Note that to run at least either a `source` or a `file` argument must be provided.  
An example macro file is also provided

## Citation

If you use this software to analyse your data please link to the GitHub page.

## Developement

This software was developed at the Holhbein Lab, Wageningen University for use in SMLM research.
It was used as a key step in [a paper by Jabermoradi, A](https://doi.org/10.1101/2021.03.03.433739).
