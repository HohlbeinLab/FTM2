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
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.gui.StackWindow;
import ij.io.Opener;
import ij.plugin.*;

import ij.process.*;
import ij.io.FileSaver;
import ij.WindowManager;
import ij.gui.YesNoCancelDialog;


import net.imagej.ops.OpService;


import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;

import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;


import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Plugin;


import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import javax.swing.*;
import java.awt.event.ActionListener;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Math.*;


//Settings for ImageJ, settings where it'll appear in the menu
//T extends RealType so this should support any image that implements this. 8b, 16b, 32b are confirmed to work
@SuppressWarnings("unchecked")
@Plugin(type = Command.class)
public class FTM2< T extends RealType< T >>  implements Command {

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
    private double ratio = 1.3;

    private boolean save_data = false;

    private Img<T> imageData;

    private final int type;

    private ImagePlus CurrentWindow;

    private double U32_SIZE = 16_777_216.0;

    private final int DONE = 0;

    private final OpService opService;

    private final LogService logService;

    private static String debug_arg_string = "";
    private static boolean runningFromMacro = false;
    private static double totalTime = 0;

    public ImagePlus ImgPlusReference;

    private boolean concat = false;
    private boolean showResults = true;

    private String savingFileName = "";
    private boolean concatRun = false;

    private String extension = "tif";
    private String argBackup = "";


    FTM2(int t, OpService op, LogService log, String command){
        this.type = t;
        this.opService = op;
        this.logService = log;
        debug_arg_string = command;

    }

    FTM2(int t, OpService op, LogService log) {
        this.type = t;
        this.opService = op;
        this.logService = log;
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
            int currentDistance = levenshtein.calculate(compareString, target);
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

    public int setup(ImagePlus imp) {
        //set the flag if we have an image already opened (and thus loaded)
        boolean pre_loaded_image = imp != null;
        String arg = "";

        if(type == 3 && !pre_loaded_image) {
            IJ.showMessage("No opened file was found.\nPlease open a file and restart.");
            return DONE;
        }

        //Default strings for the source and output directories
        String source_dir = "";
        String file_string = "";
        target_dir = "";
        File[] selected_files = null;
        MultiFileSelect fs = new MultiFileSelect(extension);

        if(debug_arg_string.equals("")){
            arg = Macro.getOptions();
        } else {
            arg = debug_arg_string;
        }
        if(arg != null && !arg.equals("")){

            runningFromMacro = true;
            final Pattern pattern = Pattern.compile("(\\w+)(=('[^']+'|\\S+))?");
            Matcher m = pattern.matcher(arg);
            String[] keywords = {
                    "source", "file","target", "start", "end", "window", "save_data", "range", "concat", "show", "hiddenConcatRun",
                    "begin", "output", "file_0", "extension"
            };
            while (m.find()) {
                if (m.groupCount() == 3) {
                    String[] keyword_val = {m.group(1), m.group(3) != null ? m.group(3).replace("'", "") : String.valueOf(false)};
                    try {
                        switch (keyword_val[0]) {
                            case "file_0":
                            case "extension":
                                extension = keyword_val[1];
                                break;
                            case "source":
                                source_dir = keyword_val[1];
                                break;
                            case "file":
                                file_string = keyword_val[1];
                                break;
                            case "target":
                                target_dir = keyword_val[1];
                                break;
                            case "begin":
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
                            case "range":
                                U32_SIZE = Double.parseDouble(keyword_val[1]);
                                break;
                            case "concatenate":
                                concat = true;
                                break;
                            case "concat":
                                concat = Boolean.parseBoolean(keyword_val[1]);
                                break;
                            case "show":
                                if(keyword_val[1].equals(""))
                                    showResults = true;
                                else
                                    showResults = Boolean.parseBoolean(keyword_val[1]);
                                break;
                            case "hiddenConcatRun":
                                concatRun = Boolean.parseBoolean(keyword_val[1]);
                                break;
                            case "output":
                                if(!keyword_val[1].equals("[]")){
                                    target_dir = keyword_val[1];
                                }
                                break;
                            default:
                                logService.error("Keyword '" + keyword_val[0] + "' not found\nDid you mean: " + getTheClosestMatch(keywords, keyword_val[0]) + "?\nOr did you forget quotes(\") around the filepath?");
                                return DONE;
                        }
                    } catch (Exception e){
                        logService.error("Failed to parse argument:" + keyword_val[1]);
                        return DONE;
                    }
                } else {
                    logService.error("Malformed argument String. Did you remember to format it as keyword=value and wrap filepaths with quotes? Entire argument string was:" + arg);
                    return DONE;
                }
            }

            if (type != 3 && source_dir.equals("") && file_string.equals("")) {
                logService.error("Argument string must contain source or file variables.");
                return DONE;
            }

            if (save_data  && target_dir.equals("")) {
                logService.error("When saving is enabled, a target directory must be provided");
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
            gd.addCheckbox("Concatenate Files?", concat);
            gd.addToSameRow();
            gd.addMessage("This will treat files from the folder/selected files as one large continous dataset if they have the same dimension and bitdepth. Check this to process all files as one.");
            gd.addCheckbox("Save Image?", save_data);
            gd.addToSameRow();
            gd.addMessage("Note that datasets larger than allocated ram will always be saved.\nYou can increase this by going to Edit > Options > Memory & Threads");
            gd.addStringField("File Extension", extension);
            gd.addCheckbox("Show output?", showResults);
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

            pre_loaded_image = type == 3;
            concat = gd.getNextBoolean();
            save_data = gd.getNextBoolean();
            extension = gd.getNextString();
            showResults = gd.getNextBoolean();
            target_dir = gd.getNextString();
        }

        if (concatRun)
            pre_loaded_image = false;

        //If we wanted a preloaded image, but nothing is opened = error
        if(pre_loaded_image && imp == null){
            logService.error("No file was open.");
            return DONE;
        }
        //We want to pull from a source directory, but none was provided = error
        if (!pre_loaded_image && source_dir.equals("") && selected_files == null && file_string.equals("")) {
            logService.error("Error: No source directory or file was provided.");
            return DONE;
        }


        //If it contains backwards slashes, replace them with forward ones
        if(!pre_loaded_image && selected_files == null && !source_dir.equals("")){
            source_dir = source_dir.replace('\\', '/');
            if (!source_dir.endsWith("/")) source_dir += "/";
        }

        //The source directory doesn't exist, so we error
        if(selected_files == null && !pre_loaded_image && !(new File(source_dir)).exists() && file_string.equals("")){
            logService.error("Error: source directory " + source_dir + " does not exist.");
            return DONE;
        }

        //If the target directory doesn't exist, we try to create it, if that fails, we error
        if (save_data && !(new File(target_dir)).exists()){
            if(!new File(target_dir).mkdir()) {
                logService.error("Error: Failed to create target directory " + target_dir);
            }
        }

        if(source_dir.equals("") && !file_string.equals("")) {
            concat = true;
        }


        if(!concat && type != 3){
            // this is hacky, but works

            File[] listOfFiles = {}; //Will contain the list of File objects from either the file select or the folder select

            if (selected_files == null){//Selected files will be a File[] that contains preselected files
                listOfFiles = new File(source_dir).listFiles();
            } else if (file_string.equals("")){ //File string is an object that can be passed from the command line
                listOfFiles = selected_files;
            }

            if(listOfFiles == null || listOfFiles.length == 0){
                logService.error("Folder is empty!");
                return DONE;
            }


            if(listOfFiles.length > 1){
                String constantCommand = " start=" + start
                        + " end=" + end
                        + " window=" + window
                        + " save_data=" + save_data
                        + " range=" + U32_SIZE
                        + " concat=" + true
                        + " show=" + showResults
                        + " hiddenConcatRun=" + true;

                for(File file : listOfFiles){
                    if(!file.getName().contains("." + extension))
                        continue;

                    String command = "file=\"" + file.getAbsolutePath() + "\""
                            + constantCommand;

                    if(!target_dir.equals("")) command += " target=\"" + target_dir + "\"";

                    logService.info("Processing file: " + file.getAbsolutePath());

                    FTM2<T> tempFTM = new FTM2<>(1, opService, logService, command);
                    tempFTM.run();
                    if(!showResults && tempFTM.ImgPlusReference != null) tempFTM.ImgPlusReference.close();
                }
                logService.info("Finished processing all files seperately");
                return DONE;
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

            if((listOfFiles == null || listOfFiles.length == 0) && file_string.equals("")){
                logService.error("Folder is empty");
                return DONE;
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
                    logService.error("Could not open: " + file_string);
                    return DONE;
                }

            }

            //If the entire file can fit into RAM, we can skip a lot of processing
            //The ratio is to provide a buffer for extra objects
            all_fits = total_disk_size < (max_bytes / ratio);

            if(all_fits){ //All data can fit into memory at once
                IJ.showStatus("Creating stacks");


                //Load the images into memory as a single stack. This will work when its multiple seperate files

                // Marks if the folder picked contains non-tiff files
                // If this occurs, we use the selected files method, discarding any non-tif files
                boolean dirty_folder = false;
                File[] checked_files;
                if(!source_dir.equals("")){
                    File dir = new File(source_dir);
                    for(File file : Objects.requireNonNull(dir.listFiles())){
                        if(!file.getName().endsWith("." + extension)) dirty_folder = true;
                    }
                    checked_files = dir.listFiles((dir1, name) -> name.endsWith("." + extension));
                    if(dirty_folder) selected_files = checked_files;
                }


                if ((selected_files != null || dirty_folder) && file_string.equals("")) {
                    ImagePlus[] temp_imgs = new ImagePlus[selected_files.length];
                    for(int i = 0; i < selected_files.length; i++){
                        try {
                            temp_imgs[i] = new Opener().openImage(selected_files[i].getAbsolutePath());
                        } catch (Exception e) {
                            logService.error("Failed to open file: " + selected_files[i].getAbsolutePath());
                            return DONE;
                        }
                    }
                    try {
                        ImgPlusReference = new Concatenator().concatenateHyperstacks(temp_imgs,   temp_imgs[0].getTitle() + "_concatenated", false);
                    } catch (Exception e) {
                        logService.error("One or more of your files might not have the same dimension");
                        return DONE;
                    }

                } else if (!file_string.equals("")) {
                    //One file to open via the commandline
                    try {
                        ImgPlusReference = new Opener().openImage(file_string);
                    } catch (Exception e) {
                        logService.error("Failed to open file: " + file_string);
                        return DONE;
                    }
                    if(ImgPlusReference == null){
                        logService.error("Failed to open file: " + file_string);
                        return DONE;
                    }

                } else {
                    //Open all files inside the provided folder
                    ImgPlusReference = new FolderOpener().openFolder(source_dir);
                }


                //Wrap the ImagePlus into an Img<T>
                //This will not copy the data! Merely reference it
                imageData = ImageJFunctions.wrapReal(ImgPlusReference);
                CurrentWindow = ImgPlusReference;

                //Display the selected images to show they were loaded
                ImgPlusReference.show();

                //Calculate the total amount of slices
                total_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));

                //Get bits per pixel
                bit_depth = imageData.firstElement().getBitsPerPixel();

                logService.info("Loaded opened image with " + total_size + " slices with size " + total_disk_size + " as normal stack");
            }  else { //All the data does not fit into memory
                IJ.showStatus("Creating Virtualstack(s)");

                slice_height = -1;
                slice_width = -1;
                bit_depth = -1;

                if(file_string.equals("")){
                    int Stack_no = 0; //Keeps track of how many stacks
                    for (int i = 0; i < listOfFiles.length; i++) {
                        //Check if the File Object is a tif
                        if (listOfFiles[i].isFile() && listOfFiles[i].getName().endsWith("." + extension)) {
                            if(savingFileName.equals(""))
                                savingFileName = listOfFiles[i].getName();
                            //Load the file into memory as a VirtualStack
                            vstacks.add(IJ.openVirtual(listOfFiles[i].getPath()).getStack());

                            //Get some information from the first stack
                            //Once the information is set, we sanity check the data to ensure the bitdepth and resolution is the same
                            if(bit_depth == -1){
                                slice_height = vstacks.get(0).getHeight();
                                slice_width = vstacks.get(0).getWidth();
                                bit_depth = vstacks.get(0).getBitDepth(); // bitdepth
                            } else if ((vstacks.get(Stack_no).getHeight()  - slice_height) +
                                    (vstacks.get(Stack_no).getWidth()  - slice_width)
                                    != 0){
                                logService.error("The dimensions or bitdepth of " + listOfFiles[i].getAbsolutePath() + " did not match the values of the first file");
                                return DONE;
                            }

                            //Get some information specific for each stack
                            slice_intervals.add(vstacks.get(Stack_no).size() + total_size);
                            total_size += vstacks.get(Stack_no).size();
                            Stack_no++;

                            logService.info(i + ", " + listOfFiles[i].getPath() + ", " + vstacks.get(Stack_no - 1).size() + " slices as virtual stack");
                        }
                    }
                } else {
                    savingFileName = new File(file_string).getName();
                    vstacks.add(IJ.openVirtual(file_string).getStack());
                    slice_height = vstacks.get(0).getHeight();
                    slice_width = vstacks.get(0).getWidth();
                    bit_depth = vstacks.get(0).getBitDepth();

                    slice_intervals.add(vstacks.get(0).size() + total_size);
                    total_size += vstacks.get(0).size();

                    logService.info(file_string+ ", " + vstacks.get(0).size() + " slices as virtual stack");
                }

                //Even if you don't want to save, if the file is too large, it will have to happen
                if(!all_fits && !save_data) {
                    IJ.showMessage("File is too large to not be cached to disk.");
                    save_data = true;
                }

                logService.info("Loaded " + total_size + " slices as virtual stack with size " +String.format("%.3f",  ((double)total_disk_size)/(double)(1024*1024*1024)) + " GB");

            }
        } else { //The image is already loaded in imageJ in the ImagePlus imp object

            //Wrap the ImagePlus in an imglib2 Img object for faster processing
            //This is a reference and not a copy
            imageData = ImageJFunctions.wrapReal(imp);

            CurrentWindow = imp;
            ImgPlusReference = imp;
            //Get some information about the file
            //We do not obtain width and height since these arent needed
            total_disk_size = (long) imp.getSizeInBytes();
            bit_depth = imageData.firstElement().getBitsPerPixel();
            total_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));

            //Since it is already loaded, it will fit for sure
            all_fits = true;

            logService.info("Loaded already opened image with " + total_size + " slices with size " + total_disk_size + " as normal stack");
        }

        //Ensure we have a stack, and not a single frame
        if(total_size <= 1){
            logService.error("Error: Stack must have size larger than 1.");
        }

        //Ensure the bitdepth is byte aligned. If no bitdepth is found, set it to 16.
        //Above 32 is not supported
        if (bit_depth == 0) bit_depth = 16;
        else if (bit_depth <= 8) bit_depth = 8;
        else if (bit_depth <= 16) bit_depth = 16;
        else if (bit_depth <= 32) {bit_depth = 32; ratio = 4;}
        else logService.error("Bitdepth not Supported");

        if(end == 0) end = total_size; //If the end var is 0, it means process all slices
        if(end > total_size) end = total_size; //If the end is set to above the total size, set it to the total size
        if(window > total_size) window = total_size; //If the window is set to above the total size, set it to the total size


        if(all_fits && imageData.firstElement() instanceof FloatType) {
            double[] result = computeMinMax(imageData.iterator());

            final double temp_min = result[0];
            final double temp_max = min(result[1], U32_SIZE);

            if ((abs(imageData.firstElement().getRealFloat())%1.0> 0.0 |result[1] > U32_SIZE )) {
                if(!runningFromMacro) {
                    if (abs(imageData.firstElement().getRealFloat()) % 1.0 > 0.0)
                        IJ.showMessage("An image with 32b float values was detected.\nThis might lead to data precision loss.\nConsider converting the data to 32b Integer.");
                    else
                        IJ.showMessage("An image with 32b values above 16.777.216,0 was deteced.\nThis range is not fully.\nThis might lead to data precision loss");
                }
                // This method only supports integer values
                // 32b images can be float however
                // this creates a mapping from the original values between 0 and U32_SIZE
                // This loses image precision, but how much depends on the range of values in input
                // I recommend converting it to 32b or 16b Integers to prevent this loss


                imageData.forEach(t -> t.setReal(((t.getRealFloat() - temp_min) * (U32_SIZE) / (temp_max - temp_min))));
            }


            //Convert the image to unsigned ints for further processing
            //This doesnt change the data, just changes the container type.
            //This step does not cause precision loss

            imageData = (Img<T>) opService.convert().uint32(imageData);
        } else if( bit_depth == 32 && !runningFromMacro) {
            IJ.showMessage("A 32b image was detected.\nIf this is a float image, it might lead to precision loss.\nIf the image contains integer values, ensure the maximum value does not exceed 6.777.216,0.");
        }


        //We want to save to a target directory, but none was provided = error
        if (target_dir.equals("") && save_data) {
            logService.error("Error: No output directory was provided.");
            return DONE;
        }

        //Since save data might be changed, we only edit the path here
        if(save_data){
            target_dir = target_dir.replace('\\', '/');
            if (!target_dir.endsWith("/")) target_dir += "/";
        }

        System.gc();

        //Since ImageJ plugins are a bit wonky, we have to tell it we do not need an image open
        return 1;
    }

    //This is the function that actually gets called by ImageJ
    //It gets the current image that is selected, and passes that on to the setup and run function
    //If DONE is returned by setup, it does not run the run function
    @Override
    public void run() {
        logService.info("Fast Temporal Median 2");
        ImagePlus openImage = WindowManager.getCurrentImage();
        if(setup(openImage) != DONE) {

            //Set some variables to measure how long the entire script, and how long just saving takes

            long savingTime = 0;
            long stopTime = 0;
            long startTime = System.nanoTime();

            if (!all_fits) {
                //Calculate the slice size in bytes and with that, the amount of slices that can be loaded at once with some buffer
                //Window slices are subtracted because these are added on to the start and end of each bracket for overlap
                int slice_size = (slice_height * slice_width * bit_depth) / 8;
                int slices_that_fit = min((int) (max_bytes / slice_size / ratio) - window, total_size);


                ArrayList<int[]> brackets = new ArrayList<>(); //Will contain the brackets of slices that will beloaded

                //Slice the entire batch up into brackets that contain the starting frame and the end frame
                int slices_left = total_size;
                int lower_end = start;
                while (slices_left > 0) {
                    slices_left -= slices_that_fit;

                    // If the leftover frames are lower than window, it wont calculate properly
                    // So we add those frames to the rest
                    // This could be an issue on extremely small ram sizes < 300 MB orso
                    if (slices_left < window) {
                        slices_left = 0;
                        brackets.add(new int[]{lower_end, end});
                    } else {
                        brackets.add(new int[]{lower_end, min(slices_that_fit + lower_end, end)});
                    }
                    lower_end = min(slices_that_fit + lower_end, end);
                }


                ImageStack temp_stack; //Onto this stack the slices will be put before being processed
                for (int k = 0; k < brackets.size(); k++) {
                    int[] t = brackets.get(k); //Get the start and end slice numbers

                    temp_stack = new ImageStack(slice_width, slice_height); //Create a new Imagestack, flushing the old one
                    //the start and end are either the start/end or the values in t +- window/2
                    //This currently only supports look-around, not lookback or lookforward
                    int s = t[0] == start ? start : t[0] - window / 2;
                    int e = t[1] == end ? end : t[1] + window / 2;

                    int temp_index; //Index into which stack inside vstacks should be accesed
                    int temp_prev_sizes = 0; //What is the offset of the frame_number (i) compared to the size of the current stack

                    //Set the temp_index and the prev_sizes to their correct start values for the current bracket
                    for (temp_index = 0; vstacks.get(temp_index).size() + temp_prev_sizes < s; temp_index++)
                        temp_prev_sizes += vstacks.get(temp_index).size();

                    //Load the frames, as defined by s and e, into the temp_stack from disk, loading them into memory
                    //If the current stack runs out, temp index is increased, as is prev_sizes
                    for (int i = s; i <= e; i++) {
                        if (i > slice_intervals.get(temp_index)) {
                            temp_prev_sizes += vstacks.get(temp_index).size();
                            temp_index++;


                        }
                        temp_stack.addSlice("" + i, vstacks.get(temp_index).getProcessor(i - temp_prev_sizes));
                    }

                    logService.info("Loaded from slice " + s + " till slice " + e);

                    long intertime = System.nanoTime();

                    //Wrap the temp_stack into an imageplus and then an Img Object
                    //This creates references, not copies
                    ImagePlus temp_imp = new ImagePlus("", temp_stack);
                    Img<T> temp_imglib = ImageJFunctions.wrapReal(temp_imp);

                    //We need to do this check because otherwise a 32b float might sneak through
                    if (temp_imglib.firstElement() instanceof FloatType) {

                        double[] result = computeMinMax(temp_imglib.iterator());

                        final double temp_min = result[0];
                        final double temp_max = min(result[1], U32_SIZE);

                        if (abs(temp_imglib.firstElement().getRealFloat()) % 1.0 > 0.0 | result[1] > U32_SIZE) {
                            temp_imglib.forEach(pixel -> pixel.setReal(((pixel.getRealFloat() - temp_min) * (U32_SIZE) / (temp_max - temp_min))));
                        }

                        temp_imglib = (Img<T>) opService.convert().uint32(temp_imglib);
                        temp_imp.close();

                        System.gc();
                    }

                    //Process the data with the defined window
                    //This happens in place
                    TemporalMedian.main(temp_imglib, window, bit_depth, 0, (int) temp_imglib.dimension(2));
                    stopTime += (System.nanoTime() - intertime);


                    if (bit_depth == 32) {

                        temp_stack = ImageJFunctions.wrapFloat(temp_imglib, "Result").getStack();

                        System.gc();
                    }

                    //Since the first window/2 and last window/2 frames are there just for overlap, we do not need these
                    ImageStack final_stack = new ImageStack(slice_width, slice_height);

                    //Create a reference in the final_stack for all the frames we want(t[0] to t[1]), unless it is the start or end.
                    final int starting_value = t[0] == start ? 1 : window / 2 + 1;
                    final int ending_value = (t[1] == end ? temp_stack.size() : temp_stack.size() - window / 2 - 1);

                    for (int j = starting_value; j <= ending_value; j++) {
                        final_stack.addSlice(temp_stack.getProcessor(j));
                    }

                    //Try to save the file and record how long this takes
                    //If it fails, error
                    //Saving time is recorded since it might indicate to an end user their drive is the limiting factor
                    intertime = System.nanoTime();
                    if (!saveImagePlus(Paths.get(target_dir, "/" + savingFileName + "_" + (k + 1) + "." + extension).toString(), new ImagePlus("", final_stack))) {
                        logService.error("Failed to write to:" +Paths.get(target_dir, "/part_" + (k + 1) + "." + extension).toString());
                        System.exit(0);
                    }
                    savingTime += (System.nanoTime() - intertime);

                    //We gc not that often, since even with default settings <10 brackets will be used normally
                    System.gc();
                }

                if(showResults) {
                    //Open all created files as virtualstacks and display them
                    //This is not able to be done in a single window afaik
                    //The contrast command is to ensure the visualisation is correct since the min and max changed.
                    for (int k = 0; k < brackets.size(); k++) {
                        IJ.openVirtual(target_dir + "/" + savingFileName + "_"   + (k + 1) + "." + extension).show();
                        IJ.run("Enhance Contrast", "saturated=0.0");
                    }
                }

            } else {

                long interTime = System.nanoTime();
                //Then process the data, either on the smaller view or the entire dataset
                TemporalMedian.main(imageData, window, bit_depth, start - 1, end);

                stopTime = System.nanoTime() - interTime;
                //This is just to refresh the image

                //this crops the image if need be
                if (start > 1 | end < total_size) {
                    ImagePlus TempReference = new OwnSubStackMaker().stackRange(ImgPlusReference, start, end, ImgPlusReference.getTitle());
                    //ImagePlus test = new SubstackMaker().makeSubstack(ImgPlusReference, "delete " + start + "-" + end);
                    ImgPlusReference.close(); //Close the old one
                    ImgPlusReference = TempReference; //Re-reference the reference
                }


                if (bit_depth == 32) {
                    //ImageJ doesnt want to display 32b int data, so i have to cast it to 32b float.
                    //this technically leads to precision loss, but this is unlikely as the values would have to be >U32_SIZE
                    // which i prevent.
                    //This is not an issue with saving

                    CurrentWindow.close();

                    ImgPlusReference = ImageJFunctions.wrapFloat(imageData, "Result");
                }

                if(showResults) {
                    ImgPlusReference.show();
                    String title = ImgPlusReference.getTitle();
                    if (title.endsWith("." + extension)) {
                        title = title.substring(0, title.length() - 1 - extension.length());
                    }
                    ImgPlusReference.setTitle(title + "_median_corrected");

                    //Run the contrast command to readjust the min and max
                    IJ.run("Enhance Contrast", "saturated=0.0");
                }

                //If needed try to save the data
                if (save_data) {

                    String saveName;
                    if(concat)
                        saveName = Paths.get(target_dir, ImgPlusReference.getTitle().substring(0, ImgPlusReference.getTitle().length() - (showResults ? 0 : 4)).replace(" ", "_") + (showResults ? "" : "_Median_corrected") + "." + extension).toString();
                    else
                        saveName = Paths.get(target_dir, ImgPlusReference.getTitle().substring(0, ImgPlusReference.getTitle().length() - (showResults ? 0 : 4)).replace(" ", "_") + (showResults ? "" : "_Median_corrected")  + "_concatenated." + extension).toString();

                    if(!saveImagePlus(saveName, ImgPlusReference)) {
                        logService.error("Failed to write to:" + saveName);
                    }
                }
            }


            startTime = System.nanoTime() - startTime;
            //Print some extra information about how long everything took and the processing speed


            double spendTime = (double) stopTime / 1000000000;
            double savedTime = (double) savingTime / 1000000000;
            double allTime = (double) startTime / 1000000000;
            totalTime += spendTime;
            logService.info("Total took " + String.format("%.3f", allTime) + " s");
            logService.info("Processing took " + String.format("%.3f", spendTime) + " s");
            if (savingTime != 0) logService.info("Saving took " + String.format("%.3f", savedTime) + " s");
            logService.info("Processed " + (end - start + 1) + " frames at " + String.format("%.1f", (total_disk_size / (1024 * 1024) / spendTime)) + " MB/s");

            IJ.showStatus("Finished Processing!");
            if(!concatRun && !runningFromMacro)
                IJ.showMessage("Finished Applying Faster Temporal Median.\nProcessed " + (end - start + 1) + " frames in " + String.format("%.3f", allTime) + " seconds.");

        }

        debug_arg_string = ""; // Need to reset the string properly
    }


    //Only used when debugging from an IDE
    public static void main(String[] args) {
        net.imagej.ImageJ ij = new net.imagej.ImageJ();
        ij.ui().showUI();



        //ImagePlus imp = IJ.openImage("F:\\ThesisData\\input2\\tiff_file." + extension);

        //imp.show();
        // String target_folder = "F:\\ThesisData\\output";
        String target_folder = "'I:\\ThesisData\\folder with a space'";
        //debug_arg_string = "file=F:\\ThesisData\\input4\\tiff_file.tif=" + target_folder + " save_data=true";
        //debug_arg_string = "file=F:\\ThesisData\\input8_large\\tiff_file.tif target=" + target_folder + " save_data=true";
        //debug_arg_string = "file=F:\\ThesisData\\input32_large\\tiff_file.tif target=" + target_folder + " save_data=true";
        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\32btest." + extension;
        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\32bnoise." + extension;
        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\large_stack32.tif save_data=true target=" + target_folder;

        debug_arg_string = "file='I:\\ThesisData\\folder with a space\\stack_small1_median_corrected.tif' show=true window=5 save_data=true target=" + target_folder;

        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\stack_small.tif start=100 end=200";
        //debug_arg_string = "file=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\large_stack\\large_stack.tif save_data=true target=" + target_folder;
        //debug_arg_string = "source=\"H:\\ThesisData\\test_images\\Issue_3 test\" concat=false save_data=true show=true target=\"" + target_folder + "\"";
        //debug_arg_string = "file=F:\\ThesisData\\input2\\tiff_file." + extension;
        //debug_arg_string = "source=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\large_stack";
        //debug_arg_string = "source=C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder";

        //debug_arg_string = "source=F:\\ThesisData\\input save_data=true target=F:\\ThesisData\\output";
        int runs = 1;

        for(int i = 0; i < runs; i++){
            System.out.println("Run:" + (i+1));
            //ImagePlus imp = IJ.openImage("F:\\ThesisData\\input2\\tiff_file.tif");
            //imp.show();
            ij.command().run(FTM2_select_folder.class, true);

            //WindowManager.closeAllWindows();
            //for(File file: Objects.requireNonNull(new File(target_folder).listFiles()))
            //    if (!file.isDirectory())
            //        file.delete();
            System.gc();
        }
        System.out.println("Average runtime " + String.format("%.3f", totalTime/(float) runs) + " s");

    }
}

// Copied from https://imagej.nih.gov/ij/developer/source/ij/plugin/SubstackMaker.java.html
// This is done purely because the delete variable is private and set to false and is only adjustable with a gui
class OwnSubStackMaker extends SubstackMaker {
    public boolean delete = true;

    ImagePlus stackRange(ImagePlus imp, int first, int last, String title){
        ImageStack stack = imp.getStack();
        ImageStack stack2 = null;
        boolean virtualStack = stack.isVirtual();
        double min = imp.getDisplayRangeMin();
        double max = imp.getDisplayRangeMax();
        Roi roi = imp.getRoi();
        boolean showProgress = stack.size()>400 || stack.isVirtual();
        for (int i= first, j=0; i<= last; i+= 1) {
            if (showProgress) IJ.showProgress(i,last);
            int currSlice = i-j;
            ImageProcessor ip2 = stack.getProcessor(currSlice);
            ip2.setRoi(roi);
            ip2 = ip2.crop();
            if (stack2==null)
                stack2 = new ImageStack(ip2.getWidth(), ip2.getHeight());
            stack2.addSlice(stack.getSliceLabel(currSlice), ip2);
            if (delete) {
                stack.deleteSlice(currSlice);
                j++;
            }
        }
        if (delete) {
            imp.setStack(stack);
            // next three lines for updating the scroll bar
            ImageWindow win = imp.getWindow();
            StackWindow swin = (StackWindow) win;
            if (swin!=null)
                swin.updateSliceSelector();
        }
        ImagePlus substack = imp.createImagePlus();
        substack.setStack(title, stack2);
        substack.setCalibration(imp.getCalibration());
        if (virtualStack)
            substack.setDisplayRange(min, max);
        return substack;
    }
}

class levenshtein {

    static int calculate(String x, String y) {
        int[][] dp = new int[x.length() + 1][y.length() + 1];

        for (int i = 0; i <= x.length(); i++) {
            for (int j = 0; j <= y.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                }
                else if (j == 0) {
                    dp[i][j] = i;
                }
                else {
                    dp[i][j] = min(dp[i - 1][j - 1]
                                    + costOfSubstitution(x.charAt(i - 1), y.charAt(j - 1)),
                            dp[i - 1][j] + 1,
                            dp[i][j - 1] + 1);
                }
            }
        }

        return dp[x.length()][y.length()];
    }

    public static int costOfSubstitution(char a, char b) {
        return a == b ? 0 : 1;
    }

    public static int min(int... numbers) {
        return Arrays.stream(numbers)
                .min().orElse(Integer.MAX_VALUE);
    }
}

class MultiFileSelect implements ActionListener {
    String extension = ".tif";
    File[] files = null;



    MultiFileSelect(String extension){
        this.extension = extension;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getActionCommand().equals("Select Files")) {
            boolean notDone = true;
            while(notDone) {
                JFileChooser chooser = new JFileChooser();
                chooser.setMultiSelectionEnabled(true);
                chooser.showOpenDialog(new JFrame());
                files = chooser.getSelectedFiles();

                notDone = false;
                for(File file : files){
                    if(!file.getName().endsWith("." + extension)){
                        IJ.showMessage("Incorrect Extension: " + file.getName());
                        notDone = true;
                    }
                }

                if(!notDone)
                    IJ.showMessage("You selected: " + getFileNames());

            }
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
