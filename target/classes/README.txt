<html># Faster Temporal Median<br>
A rewritten version of the Fast Temporal Median Filter, enabling extra features like more bit-depth support and larger-than-ram datasets, as well as being slightly faster than previous versions<br>
<br>
## Usage<br>
<br>
The plugin currently only works on tif images with a bit-depth of 8, 16 or 32 bits.<br>
The maximum file size to be processes is not limited, images larger than ram will be written to disk.<br>
When processing more than one file, they all need to be of the same resolution and bit-depth.<br>
All selected files are concatenated in alphabetical order.<br>
<br>
The variables that can be set are:<br>
*Start - From which frame the plugin should start<br>
*End - Till which frame the plugin should run. When set to 0, it will process all frames<br>
*Window - The window of which the median will be taken<br>
<br>
*New Method - Uses a slightly newer method for determining the median which might be faster depending on your application (default: false)<br>
*Save Data - If the produced data should be saved to disk. If checked a target directory must be provided<br>
*Note that datasets larger than available RAM will always be saved. You can increase available RAM by going to Edit > Options > Memory & Threads<br>
<br>
There are three options in the Plugins > Faster Temporal Median menu.<br>
They all use the same back-end, but allow you to select files in different ways.<br>
*Select Files and Run<br>
*Select Folder and run<br>
*Use Opened Image and Run<br>
<br>
### Select Files and Run<br>
With this option you can select one or multiple files from the same folder to process.<br>
<br>
### Select Folder and Run<br>
With this option you can select a folder from which to run all files.<br>
Any subfolders or non-tif files are ignored in this.<br>
<br>
### Use Opened Image and Run<br>
With this option the selected, the selected image is used for processing.<br>
This option will be slightly faster, as the loading of data is already done.<br>
<br>
## Running from a Macro<br>
This plugin can also be run from the macro.<br>
An example: `run("Select Files and Run", "source=your_data target=your_folder start=1 end=0 window=50 save_data=0")`<br>
All keywords must be provided in the format: `keyword=value`, seperated by spaces.<br>
For true/false values, 0 or 1 could also be used.<br>
The keywords available are:<br>
*source - The path to the folder from which to process files<br>
*target - The path to which the processed data is to be saved<br>
*file - The path to the file which to process<br>
*start - From which frame the plugin should start (default: 1)<br>
*end - Till which frame the plugin should run. When set to 0, it will process all frames (default: all frames)<br>
*window - The window of which the median will be taken (default: 50)<br>
*save_data - If the produced data should be saved to disk. If set the target keyword must also be included. (default: false)<br>
*new_method - Uses a slightly newer method for determining the median which might be faster depending on your application (default: false)<br>
<br>
Note that to run at least either a `source` or a `file` argument must be provided.<br>
An example macro file is also provided<br>
</html>