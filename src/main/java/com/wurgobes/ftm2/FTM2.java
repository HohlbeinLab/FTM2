package com.wurgobes.ftm2;
/* Fast Temporal Median filter
(c) 2020 Martijn Gobes, Wageningen University.
Based on the Fast Temporal Median Filter for ImageJ by the Milstein Lab
and the Fast Temporal Median Filter by Rolf Harkes and Bram van den Broek at the Netherlands Cancer Institutes.

Calculating the median from the ranked data, and processing each pixel in parallel.
The filter is intended for pre-processing of single molecule localization data.
v0.9

The CPU implementation is based on the T.S. Huang method for fast median calculations.

Currently only supports tif files
Currently supports 8, 16, and 32 bit CPU processing

Used articles:
T.S.Huang et al. 1979 - Original algorithm for CPU implementation

This software is released under the GPL v3. You may copy, distribute and modify
the software as long as you track changes/dates in source files. Any
modifications to or software including (via compiler) GPL-licensed code
must also be made available under the GPL along with build & install instructions.
https://www.gnu.org/licenses/gpl-3.0.en.html

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

import fiji.util.gui.GenericDialogPlus;

import ij.*;
import ij.io.Opener;
import ij.plugin.*;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.*;
import ij.io.FileSaver;
import ij.WindowManager;
import ij.gui.YesNoCancelDialog;

import net.imagej.ops.OpService;

import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.view.Views;

import org.apache.commons.lang.StringUtils;
import org.scijava.Context;


import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.swing.*;
import java.awt.event.ActionListener;
import java.util.concurrent.atomic.DoubleAccumulator;

import static java.lang.Math.abs;
import static java.lang.Math.min;

class MultiFileSelect implements ActionListener {

    File[] files = null;

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getActionCommand().equals("Select Files")) {
            JFileChooser chooser = new JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            chooser.showOpenDialog(new JFrame());
            files = chooser.getSelectedFiles();
            IJ.showMessage("You selected: " + getFileNames());
        } else {
            YesNoCancelDialog answer = new YesNoCancelDialog(new JFrame(), "Clear list of files", "Do you want to clear:" + getFileNames());
            if(answer.yesPressed()) files = null;
        }
    }

    public File[] getFiles(){
        return files;
    }

    public String getFileNames(){
        if(files == null) return "No files selected";
        StringBuilder tmp = new StringBuilder();
        for(File file: files){
            tmp.append("\"").append(file.getName()).append("\" ");
        }
        return tmp.toString();
    }
}


//Settings for ImageJ, settings where it'll appear in the menu
//T extends RealType so this should support any image that implements this. 8b, 16b, 32b are confirmed to work
public class FTM2< T extends RealType< T >>  implements ExtendedPlugInFilter, PlugIn {

    public static int window = 50;
    public static int start = 1;
    public static int end = 0;

    private String target_dir;

    private final ArrayList<Integer> slice_intervals = new ArrayList<>();
    private final ArrayList<ImageStack> vstacks = new ArrayList<>();

    private int total_size = 0;
    private long total_disk_size = 0;
    private final long max_bytes = Runtime.getRuntime().maxMemory();
    private boolean all_fits = false;

    private int slice_height;
    private int slice_width;
    private int bit_depth;
    private final double ratio = 1.3;

    private boolean save_data = false;

    private Img<T> imageData;

    private final int type;

    private ImagePlus CurrentWindow;


    private static String debug_arg_string = "";
    private static double totalTime = 0;


    FTM2(int t) {
        this.type = t;
    }

    public boolean saveImagePlus(final String path, ImagePlus impP){
        //Saves an ImagePlus Object as a tiff at the provided path, returns true if succeeded, false if not
        try {
            return new FileSaver(impP).saveAsTiff(path);
        } catch (Exception e) {
            return false;
        }
    }

    public static String getTheClosestMatch(String[] strings, String target) {
        int distance = Integer.MAX_VALUE;
        String closest = null;
        for (String compareString: strings) {
            int currentDistance = StringUtils.getLevenshteinDistance(compareString, target);
            if(currentDistance < distance) {
                distance = currentDistance;
                closest = compareString;
            }
        }
        return closest;
    }


    // print input stream
    private static String GetInputStream(InputStream is) {
        StringBuilder result = new StringBuilder();
        try (InputStreamReader streamReader =
                     new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(streamReader)) {

            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    private static <T extends RealType< T >> double[] computeMinMax(Iterator<T> iterator) {
        DoubleAccumulator min = new DoubleAccumulator(Double::min, Double.POSITIVE_INFINITY);
        DoubleAccumulator max = new DoubleAccumulator(Double::max, Double.NEGATIVE_INFINITY);

        iterator.forEachRemaining(t -> {max.accumulate(t.getRealDouble()); min.accumulate(t.getRealDouble());});

        return new double[]{min.get(), max.get()};
    }

    /*
    Setup sets up a variety of variables like bitdepth and
    dimension as well as loading the imagedata requested by the user into the type required.

    arg will always be ""
    imp will contain the already opened image, if one exists, otherwise it is null
     */

    public int setup(String arg, ImagePlus imp) {
        //set the flag if we have an image already opened (and thus loaded)
        boolean pre_loaded_image = imp != null;
        if(type == 3 && !pre_loaded_image) {
            IJ.showMessage("No opened file was found.\nPlease open a file and restart.");
            return DONE;
        }

        //Default strings for the source and output directories
        String source_dir = "";
        String file_string = "";
        target_dir = "";
        File[] selected_files = null;
        MultiFileSelect fs = new MultiFileSelect();

        if(debug_arg_string.equals("")){
            arg = Macro.getOptions();
        } else {
            arg = debug_arg_string;
        }
        if(arg != null && !arg.equals("")){
            String[] arguments = arg.split(" ");
            String[] keywords = {"source", "file","target", "start", "end", "window", "save_data"};
            for(String a : arguments) {
                if (a.contains("=")) {
                    String[] keyword_val = a.split("=");
                    try {
                        switch (keyword_val[0]) {
                            case "source":
                                source_dir = keyword_val[1];
                                break;
                            case "file":
                                file_string = keyword_val[1];
                                break;
                            case "target":
                                target_dir = keyword_val[1];
                                break;
                            case "start":
                                start = Integer.parseInt(keyword_val[1]);
                                break;
                            case "end":
                                end = Integer.parseInt(keyword_val[1]);
                                break;
                            case "window":
                                window = Integer.parseInt(keyword_val[1]);
                                break;
                            case "save_data":
                                save_data = Boolean.parseBoolean(keyword_val[1]);
                                break;
                            default:
                                System.out.println("Keyword " + keyword_val[0] + " not found\nDid you mean: " + getTheClosestMatch(keywords, keyword_val[0]) + "?");
                                return DONE;
                        }
                    } catch (Exception e) {
                        System.out.println("Failed to parse argument:" + keyword_val[1]);
                        return DONE;
                    }
                } else {
                    System.out.println("Malformed token: " + a + ".\nDid you remember to format it as keyword=value?");
                    return DONE;
                }
            }
            if (source_dir.equals("") && file_string.equals("")) {
                System.out.println("Argument string must contain source or file variables.");
                return DONE;
            }

            if (save_data  && target_dir.equals("")) {
                System.out.println("When saving is enabled, a target directory must be provided");
                return DONE;
            }
        } else {
            InputStream is = FTM2.class.getResourceAsStream("/README.txt"); // OK
            String content = GetInputStream(is);



            //Custom class that allows a button to select multiple files using a JFilechooser as GenericDialog doesn't suppor this
            //Create the setup dialogue and its components
            GenericDialogPlus gd = new GenericDialogPlus("Settings for Temporal Median Filter");

            if(type == 0 | type == 2) gd.addDirectoryField("Source directory", source_dir, 50);

            if(type == 0 | type == 1) gd.addButton("Select Files", fs); //Custom button that allows for creating and deleting a list of files
            if(type == 0 | type == 1) gd.addButton("Clear Selected Files", fs);
            if(type == 3) gd.addMessage("Will use already opened file: " + imp.getTitle());

            gd.addNumericField("Window size", window, 0);
            gd.addNumericField("Begin", start, 0);
            gd.addNumericField("End (0 for all)", end, 0);
            gd.addCheckbox("Save Image?", save_data);
            gd.addToSameRow();
            gd.addMessage("Note that datasets larger than allocated ram will always be saved.\nYou can increase this by going to Edit > Options > Memory & Threads");
            gd.addDirectoryField("Output directory", target_dir, 50);
            gd.addHelp(content);



            //Show the dialogue
            gd.showDialog();

            // Exit when canceled
            if (gd.wasCanceled())
                return DONE;

            //Retrieve all the information from the dialogue,
            if(type == 0 | type == 2) source_dir = gd.getNextString();
            if(type == 0 | type == 1) selected_files = fs.getFiles();
            window = (int)gd.getNextNumber();
            start = (int)gd.getNextNumber();
            end = (int)gd.getNextNumber();
            //pre_loaded_image = gd.getNextBoolean();
            pre_loaded_image = type == 3;
            save_data = gd.getNextBoolean();
            target_dir = gd.getNextString();
        }

        //If we wanted a preloaded image, but nothing is opened = error
        if(pre_loaded_image && imp == null){
            IJ.error("No file was open.");
            return DONE;
        }
        //We want to pull from a source directory, but none was provided = error
        if (!pre_loaded_image && source_dir.equals("") && selected_files == null && file_string.equals("")) {
            IJ.error("Error: No source directory was provided.");
            return DONE;
        }


        //If it contains backwards slashes, replace them with forward ones
        if(!pre_loaded_image && selected_files == null){
            source_dir = source_dir.replace('\\', '/');
            if (!source_dir.endsWith("/")) source_dir += "/";
        }

        //The source directory doesn't exist, so we error
        if(selected_files == null && !pre_loaded_image && !(new File(source_dir)).exists()){
            IJ.error("Error: source directory " + source_dir + " does not exist.");
            return DONE;
        }

        //If the target directory doesn't exist, we try to create it, if that fails, we error
        if (save_data && !(new File(target_dir)).exists()){
            if(!new File(target_dir).mkdir()) {
                IJ.error("Error: Failed to create target directory " + target_dir);
            }
        }

        //If an image is not yet loaded, we should have the info required to load
        if(!pre_loaded_image){

            File[] listOfFiles = {}; //Will contain the list of File objects from either the file select or the folder select

            if (selected_files == null){//Selected files will be a File[] that contains preselected files
                listOfFiles = new File(source_dir).listFiles();
            } else if (file_string.equals("")){//File string is an object that can be passed from the command line
                listOfFiles = selected_files;
            }



            if(file_string.equals("")){
                //Calculate the total file size of all files to be processed
                assert listOfFiles != null;
                for(File file: listOfFiles){
                    total_disk_size += file.length();
                }
            } else {
                try {
                    total_disk_size = new File(file_string).length();
                } catch (Exception e) {
                    System.out.println("Could not open: " + file_string);
                    return DONE;
                }

            }

            //If the entire file can fit into RAM, we can skip a lot of processing
            //The ratio is to provide a buffer for extra objects
            all_fits = total_disk_size < (max_bytes / ratio);


            if(all_fits){ //All data can fit into memory at once
                IJ.showStatus("Creating stacks");

                //Load the images into memory as a single stack. This will work when its multiple seperate files
                ImagePlus temp_img;
                if (selected_files != null) {
                    ImagePlus[] temp_imgs = new ImagePlus[selected_files.length];
                    for(int i = 0; i < selected_files.length; i++){
                        try {
                            temp_imgs[i] = new Opener().openImage(selected_files[i].getAbsolutePath());
                        } catch (Exception e) {
                            System.out.println("Failed to open file: " + selected_files[i].getAbsolutePath());
                            return DONE;
                        }
                    }
                    try {
                        temp_img = new Concatenator().concatenateHyperstacks(temp_imgs, "Concatenated", false);
                    } catch (Exception e) {
                        System.out.println("One or more of your files might not have the same dimension");
                        return DONE;
                    }

                } else if (!file_string.equals("")) {
                    //One file to open via the commandline
                    try {
                        temp_img = new Opener().openImage(file_string);
                    } catch (Exception e) {
                        System.out.println("Failed to open file: " + file_string);
                        return DONE;
                    }

                } else {
                    //Open all files inside the provided folder
                    temp_img = new FolderOpener().openFolder(source_dir);
                }

                //Wrap the ImagePlus into an Img<T>
                //This will not copy the data! Merely reference it
                imageData = ImageJFunctions.wrapReal(temp_img);

                //Display the selected images to show they were loaded
                CurrentWindow = ImageJFunctions.show(imageData);

                //Calculate the total amount of slices
                total_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));

                System.out.println("Loaded opened image with " + total_size + " slices with size " + total_disk_size + " as normal stack");
            }  else { //All the data does not fit into memory
                IJ.showStatus("Creating Virtualstack(s)");

                slice_height = -1;
                slice_width = -1;
                bit_depth = -1;

                if(file_string.equals("")){
                    int Stack_no = 0; //Keeps track of how many stacks
                    for (int i = 0; i < listOfFiles.length; i++) {
                        //Check if the File Object is a tif
                        if (listOfFiles[i].isFile() && listOfFiles[i].getName().endsWith(".tif")) {
                            //Load the file into memory as a VirtualStack
                            vstacks.add(IJ.openVirtual(listOfFiles[i].getPath()).getStack());

                            //Get some information from the first stack
                            //Once the information is set, we sanity check the data to ensure the bitdepth and resolution is the same
                            if(bit_depth == -1){
                                slice_height = vstacks.get(0).getHeight();
                                slice_width = vstacks.get(0).getWidth();
                                bit_depth = vstacks.get(0).getBitDepth(); // bitdepth
                            } else if ((vstacks.get(Stack_no).getHeight() - slice_height) +
                                    (vstacks.get(Stack_no).getWidth() - slice_width) +
                                    (vstacks.get(Stack_no).getBitDepth() - bit_depth)
                                    != 0){
                                IJ.error("The dimensions or bitdepth of " + listOfFiles[i].getAbsolutePath() + " did not match the values of the first file");
                                return DONE;
                            }

                            //Get some information specific for each stack
                            slice_intervals.add(vstacks.get(Stack_no).size() + total_size);
                            total_size += vstacks.get(Stack_no).size();
                            Stack_no++;

                            System.out.println(i + ", " + listOfFiles[i].getPath() + ", " + vstacks.get(Stack_no - 1).size() + " slices as virtual stack");
                        }
                    }
                } else {
                    vstacks.add(IJ.openVirtual(file_string).getStack());
                    slice_height = vstacks.get(0).getHeight();
                    slice_width = vstacks.get(0).getWidth();
                    bit_depth = vstacks.get(0).getBitDepth();

                    slice_intervals.add(vstacks.get(0).size() + total_size);
                    total_size += vstacks.get(0).size();

                    System.out.println(file_string+ ", " + vstacks.get(0).size() + " slices as virtual stack");
                }

                //Even if you don't want to save, if the file is too large, it will have to happen
                if(!all_fits && !save_data) {
                    IJ.showMessage("File is too large to not be cached to disk.");
                    save_data = true;
                }

                System.out.println("Loaded " + total_size + " slices as virtual stack with size " +String.format("%.3f",  ((double)total_disk_size)/(double)(1024*1024*1024)) + " GB");

            }
        } else { //The image is already loaded in imageJ in the ImagePlus imp object

            //Wrap the ImagePlus in an imglib2 Img object for faster processing
            //This is a reference and not a copy
            imageData = ImageJFunctions.wrapReal(imp);

            CurrentWindow = imp;
            //Get some information about the file
            //We do not obtain width and height since these arent needed
            total_disk_size = (long) imp.getSizeInBytes();
            bit_depth = imageData.firstElement().getBitsPerPixel();
            total_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));

            //Since it is already loaded, it will fit for sure
            all_fits = true;

            System.out.println("Loaded already opened image with " + total_size + " slices with size " + total_disk_size + " as normal stack");
        }

        //Ensure we have a stack, and not a single frame
        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }

        //Ensure the bitdepth is byte aligned. If no bitdepth is found, set it to 16.
        //Above 32 is not supported
        if (bit_depth == 0) bit_depth = 16;
        else if (bit_depth <= 8) bit_depth = 8;
        else if (bit_depth <= 16) bit_depth = 16;
        else if (bit_depth <= 32) bit_depth = 32;
        else IJ.error("Bitdepth not Supported");


        if(end == 0) end = total_size; //If the end var is 0, it means process all slices
        if(end > total_size) end = total_size; //If the end is set to above the total size, set it to the total size
        if(window > total_size) window = total_size; //If the window is set to above the total size, set it to the total size
        //if (window % 2 == 0) window++; //The CPU algorithm does not work with even window sizes


        if(imageData.firstElement().getBitsPerPixel() ==  32) {
            if (all_fits && abs(imageData.firstElement().getRealFloat())%1.0> 0.0) {
                IJ.showMessage("An image with 32b float values was detected.\nThis might lead to data precision loss.\nConsider converting the data to 32b Integer.");

                // This method only supports integer values
                // 32b images can be float however
                // this creates a mapping from the original values between 0 and U32_SIZE
                // This loses image precision, but how much depends on the range of values in input
                // I recommend converting it to 32b or 16b Integers to prevent this loss

                double[] result = computeMinMax(imageData.iterator());
                final double temp_min = result[0];
                final double temp_max = min(result[1], 4_000_000.0);
                final double U32_SIZE = 4_000_000.0;


                imageData.forEach(t -> t.setReal(((t.getRealFloat() - temp_min) * (U32_SIZE) / (temp_max - temp_min))));
            }
            //Get an ops context
            Context context = new Context(OpService.class);
            OpService ops = context.getService(OpService.class);

            //Convert the image to unsigned ints for further processing
            //This doesnt change the data, just changes the container type.
            //This step does not cause precision loss
            imageData = (Img<T>) ops.convert().uint32(imageData);
        }


        //We want to save to a target directory, but none was provided = error
        if (target_dir.equals("") && save_data) {
            IJ.error("Error: No output directory was provided.");
            return DONE;
        }

        //Since save data might be changed, we only edit the path here
        if(save_data){
            target_dir = target_dir.replace('\\', '/');
            if (!target_dir.endsWith("/")) target_dir += "/";
        }

        System.gc();

        //Since ImageJ plugins are a bit wonky, we have to tell it we do not need an image open
        return DOES_8G + DOES_16 + DOES_32 + NO_IMAGE_REQUIRED + NO_UNDO;
    }

    //This is the function that actually gets called by ImageJ
    //It gets the current image that is selected, and passes that on to the setup and run function
    //If DONE is returned by setup, it does not run the run function
    @Override
    public void run(String arg){
        ImagePlus openImage = WindowManager.getCurrentImage();
        ImageProcessor Dummy = null;
        if (openImage == null){
            if(setup(arg, null) != DONE){
                run(Dummy);
            }
        } else {
            if(setup(arg, openImage) != DONE) {
                run(openImage.getProcessor());
            }
        }
    }

    @Override
    public void run(ImageProcessor ip) {
        //Set some variables to measure how long the entire script, and how long just saving takes

        long savingTime = 0;
        long stopTime = 0;
        long startTime = System.nanoTime();

        if(!all_fits) {
            //Calculate the slice size in bytes and with that, the amount of slices that can be loaded at once with some buffer
            //Window slices are subtracted because these are added on to the start and end of each bracket for overlap
            int slice_size = (slice_height * slice_width * bit_depth)/8;
            int slices_that_fit = min(min((int)(max_bytes/slice_size/ratio), end), total_size) - window;

            ArrayList<int[]> brackets  = new ArrayList<>(); //Will contain the brackets of slices that will beloaded

            //Slice the entire batch up into brackets that contain the starting frame and the end frame
            int slices_left = total_size;
            int lower_end = start;
            while(slices_left > 0){
                slices_left -= slices_that_fit;
                brackets.add(new int[]{lower_end, min(slices_that_fit + lower_end, end)});
                lower_end = min(slices_that_fit + lower_end, end);
            }

            ImageStack temp_stack; //Onto this stack the slices will be put before being processed
            for(int k = 0; k < brackets.size(); k++) {
                int[] t = brackets.get(k); //Get the start and end slice numbers

                temp_stack = new ImageStack(slice_width, slice_height); //Create a new Imagestack, flushing the old one
                //the start and end are either the start/end or the values in t +- window/2
                //This currently only supports look-around, not lookback or lookforward
                int s = t[0] == start ? start : t[0] - window/2;
                int e = t[1] == end ? end : t[1] + window/2;

                int temp_index; //Index into which stack inside vstacks should be accesed
                int temp_prev_sizes = 0; //What is the offset of the frame_number (i) compared to the size of the current stack

                //Set the temp_index and the prev_sizes to their correct start values for the current bracket
                for(temp_index = 0; vstacks.get(temp_index).size() + temp_prev_sizes < s; temp_index++) temp_prev_sizes += vstacks.get(temp_index).size();

                //Load the frames, as defined by s and e, into the temp_stack from disk, loading them into memory
                //If the current stack runs out, temp index is increased, as is prev_sizes
                for(int i = s; i <= e; i++ ) {
                    if(i > slice_intervals.get(temp_index)){
                        temp_prev_sizes += vstacks.get(temp_index).size();
                        temp_index++;
                    }
                    temp_stack.addSlice("" + i, vstacks.get(temp_index).getProcessor(i - temp_prev_sizes));
                }

                System.out.println("Loaded from slice " +  s + " till slice " + e);

                //Wrap the temp_stack into an imageplus and then an Img Object
                //This creates references, not copies
                ImagePlus temp_imp = new ImagePlus("", temp_stack);
                Img<T> temp_imglib = ImageJFunctions.wrapReal(temp_imp);

                //Process the data with the defined window
                //This happens in place
                long intertime = System.nanoTime();
                TemporalMedian.main(temp_imglib, window, bit_depth, 0);
                stopTime += (System.nanoTime() - intertime);

                //Since the first window/2 and last window/2 frames are there just for overlap, we do not need these
                ImageStack final_stack = new ImageStack(slice_width, slice_height);

                //Create a reference in the final_stack for all the frames we want(t[0] to t[1]), unless it is the start or end.
                for(int j = t[0] == start ? 1 : window/2 + 1; j <= (t[1] == end ? temp_stack.size() : temp_stack.size() - window/2 - 1); j++){
                    final_stack.addSlice(temp_stack.getProcessor(j));
                }

                //Try to save the file and record how long this takes
                //If it fails, error
                //Saving time is recorded since it might indicate to an end user their drive is the limiting factor
                intertime = System.nanoTime();
                if(!saveImagePlus(target_dir + "\\part_" + (k + 1)+ ".tif", new ImagePlus("", final_stack))){
                    IJ.error("Failed to write to:" + (target_dir + "\\part_" + (k + 1)+ ".tif") + "\\Median_corrected.tif");
                    System.exit(0);
                }
                savingTime += (System.nanoTime() - intertime);

                //We gc not that often, since even with default settings <10 brackets will be used normally
                System.gc();
            }
            //Open all created files as virtualstacks and display them
            //This is not able to be done in a single window afaik
            //The contrast command is to ensure the visualisation is correct since the min and max changed.
            for(int k = 0; k < brackets.size(); k++) {
                IJ.openVirtual(target_dir + "\\part_" + (k + 1)+ ".tif").show();
                IJ.run("Enhance Contrast", "saturated=0.0");
            }

        } else {
            RandomAccessibleInterval<T> data;
            if(start > 1 | end < total_size) {
                //Get a reference for only the data if the start and end don't equal the whole file
                data = Views.interval(imageData, new long[] {0, 0, start }, new long[] {imageData.dimension(0) - 1, imageData.dimension(1) - 1, end - 1});
            } else {
                data = imageData;
            }


            long interTime = System.nanoTime();
            //Then process the data, either on the smaller view or the entire dataset

            TemporalMedian.main(data, window, bit_depth, start - 1);

            stopTime = System.nanoTime() - interTime;
            //This is just to refresh the image

            CurrentWindow.close();

            if(bit_depth == 32) {
                //ImageJ doesnt want to display 32b int data, so i have to cast it to 32b float.
                //this technically leads to precision loss, but this is unlikely as the values would have to be >U32_SIZE
                // which i prevent.
                //This is not an issue with saving

                ImageJFunctions.wrapFloat(data, "").show();
            } else {
                ImageJFunctions.show(data);
            }




            //Run the contrast command to readjust the min and max
            IJ.run("Enhance Contrast", "saturated=0.0");

            //If needed try to save the data
            if (save_data && !saveImagePlus(target_dir + "\\Median_corrected.tif", ImageJFunctions.wrap(data, "Median_Corrected"))){
                IJ.error("Failed to write to:" + target_dir + "\\Median_corrected.tif");
                System.exit(0);
            }
        }

        IJ.showStatus("Finished Processing!");

        startTime = System.nanoTime() - startTime;
        //Print some extra information about how long everything took and the processing speed


        double spendTime = (double)stopTime/1000000000;
        double savedTime = (double)savingTime/1000000000;
        double allTime = (double)startTime/1000000000;
        totalTime += spendTime;
        System.out.println("Total took " + String.format("%.3f", allTime) + " s");
        System.out.println("Processing took " + String.format("%.3f", spendTime) + " s");
        if(savingTime != 0) System.out.println("Saving took " + String.format("%.3f", savedTime) + " s");
        System.out.println("Processed " + (end - start + 1) + " frames at " +  String.format("%.1f", (total_disk_size/(1024*1024)/spendTime))+ " MB/s");
    }


    //Unused override functions
    @Override
    public int showDialog(ImagePlus imagePlus, String s, PlugInFilterRunner plugInFilterRunner) {
        return 0;
    }

    @Override
    public void setNPasses(int i) { }

    //Only used when debugging from an IDE
    public static void main(String[] args) {
        new ImageJ();
        //ImagePlus imp = IJ.openImage("F:\\ThesisData\\input2\\tiff_file.tif");

        //imp.show();
        String target_folder = "F:\\ThesisData\\output";
        //debug_arg_string = "file=F:\\ThesisData\\input4\\tiff_file.tif target=" + target_folder + " save_data=true";
        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\32btest.tif";
        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\32bnoise.tif";
        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\large_stack32.tif save_data=true";

        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\large_stack8.tif";

        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\stack_small.tif";
        debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\large_stack\\large_stack.tif";
        //debug_arg_string = "file=F:\\ThesisData\\input2\\tiff_file.tif";

        int runs = 1;

        for(int i = 0; i < runs; i++){
            System.out.println("Run:" + (i+1));
            ImagePlus imp = IJ.openImage("C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\large_stack\\large_stack.tif");
            imp.show();
            IJ.runPlugIn(FTM2_select_files.class.getName(), "");
            //WindowManager.closeAllWindows();
            for(File file: Objects.requireNonNull(new File(target_folder).listFiles()))
                if (!file.isDirectory())
                    file.delete();
            System.gc();
        }
        System.out.println("Average runtime " + String.format("%.3f", totalTime/(float) runs) + " s");
    }
}