package com.wurgobes.imagej;
/* Fast Temporal Median filter
(c) 2020 Martijn Gobes, Wageningen University.
Based on the Fast Temporal Median Filter for ImageJ by the Milstein Lab
and the Fast Temporal Median Filter by Rolf Harkes and Bram van den Broek at the Netherlands Cancer Institutes.


Calculating the median from the ranked data, and processing each pixel in parallel.
The filter is intended for pre-processing of single molecule localization data.
v0.9

The CPU implementation is based on the T.S. Huang method for fast median calculations.
The GPU implementation makes use of CLIJ2, by Robert Haase.

This program currently support any file size processing via the GPU method and virtualstacks.
A much faster method is included via the CPU, but this required all imagedata to fit in memory.
One can manually allocate more memory to the JavaVM to do allow for larger images.

Currently only supports tif files
Currently supports 8, 16, and 32 bit CPU processing
Currently supports 8 and 16 bit GPU processing

GPU and CPU implementation should be identical, but if discrepansies occur, one can force the GPU method

Used articles:
T.S.Huang et al. 1979 - Original algorithm for CPU implementation
Robert Haase et al. 2020 CLIJ: GPU-accelerated image processing for everyone

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


import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.view.Views;

import org.apache.commons.lang.StringUtils;
import org.scijava.Priority;
import org.scijava.plugin.*;

import org.scijava.command.Command;


import java.awt.event.ActionEvent;
import java.io.File;
import java.util.*;

import javax.swing.*;
import java.awt.event.ActionListener;

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

    public String getFileNamesRegex(){
        if(files == null) return "No files selected";
        StringBuilder tmp = new StringBuilder().append("(");
        for(File file: files){
            tmp.append(file.getName()).append("|");
        }
        return tmp.substring(0, tmp.length()-1) + ")";
    }
}


//Settings for ImageJ, settings where it'll appear in the menu

//T extends RealType so this should support any image that implements this. 8b, 16b, 32b are confirmed to work
@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median>EveryThing", label="FTM2", priority = Priority.VERY_HIGH)
public class FTM2< T extends RealType< T >>  implements ExtendedPlugInFilter, Command {

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

    boolean save_data = false;

    private Img<T> imageData;

    private int type = 0;

    FTM2(int t) {
        type = t;
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
        String source_dir="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release
        String file_string = "";
        target_dir="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release
        File[] selected_files = null;
        MultiFileSelect fs = new MultiFileSelect();

        arg = Macro.getOptions();
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
            if ((source_dir.equals("") && file_string.equals("")) | target_dir.equals("") ) {
                System.out.println("Argument string must contain source and target variables.");
                return DONE;
            }
        } else {

            //Custom class that allows a button to select multiple files using a JFilechooser as GenericDialog doesn't suppor this
            //Create the setup dialogue and its components
            GenericDialogPlus gd = new GenericDialogPlus("Settings");
            gd.addMessage("Temporal Median Filter");

            if(type == 0 | type == 2) gd.addDirectoryField("Source directory", source_dir, 50);

            if(type == 0 | type == 1) gd.addButton("Select Files", fs); //Custom button that allows for creating and deleting a list of files
            if(type == 0 | type == 1) gd.addToSameRow();
            if(type == 0 | type == 1) gd.addButton("Clear Selected Files", fs);
            if(type == 3) gd.addMessage("Will use already opened file:" + imp.getTitle());
            gd.addDirectoryField("Output directory", target_dir, 50);
            gd.addNumericField("Window size", window, 0);
            gd.addNumericField("Begin", start, 0);
            gd.addNumericField("End (0 for all)", end, 0);
            //gd.addCheckbox("Use open image?", pre_loaded_image);
            gd.addCheckbox("Save Image?", save_data);
            gd.addToSameRow();
            gd.addMessage("Note that datasets larger than allocated ram will always be saved.\nYou can increase this by going to Edit > Options > Memory & Threads");

            //Show the dialogue
            gd.showDialog();

            // Exit when canceled
            if (gd.wasCanceled())
                return DONE;

            //Retrieve all the information from the dialogue,
            if(type == 0 | type == 2) source_dir = gd.getNextString();
            if(type == 0 | type == 1) selected_files = fs.getFiles();
            target_dir = gd.getNextString();
            window = (int)gd.getNextNumber();
            start = (int)gd.getNextNumber();
            end = (int)gd.getNextNumber();
            //pre_loaded_image = gd.getNextBoolean();
            pre_loaded_image = type == 3;
            save_data = gd.getNextBoolean();
        }


        if(pre_loaded_image && imp == null){
            IJ.error("No file was open."); return DONE;
        }

        if (!pre_loaded_image && source_dir.equals("") && selected_files == null) {
            IJ.error("Error: No source directory was provided.");
            return DONE;
        }
        if (target_dir.equals("") && save_data) {
            IJ.error("Error: No output directory was provided.");
            return DONE;
        }

        if(!pre_loaded_image && selected_files == null){
            source_dir = source_dir.replace('\\', '/');
            if (!source_dir.endsWith("/")) source_dir += "/";
        }



        if(selected_files == null && !pre_loaded_image && !(new File(source_dir)).exists()){
            IJ.error("Error: source directory " + source_dir + " does not exist.");
            return DONE;
        }

        if (save_data && !(new File(target_dir)).exists()){
            if(!new File(target_dir).mkdir()) {
                IJ.error("Error: Failed to create target directory " + target_dir);
            }
        }


        if(!pre_loaded_image){

            File[] listOfFiles;

            if (selected_files == null){
                listOfFiles = new File(source_dir).listFiles();
            } else if (file_string.equals("")){
                listOfFiles = selected_files;
            } else {
                try {
                    listOfFiles = new File[]{new File(file_string)};
                } catch (Exception e) {
                    IJ.error("Could not open: " + file_string);
                    return DONE;
                }
            }

            assert listOfFiles != null;


            for(File file: listOfFiles){
                total_disk_size += file.length();

            }
            all_fits = total_disk_size < max_bytes / ratio;


            if(all_fits){ //All data can fit into memory at once
                IJ.showStatus("Creating stacks");

                ImagePlus temp_img;
                if (selected_files != null) {
                    temp_img = FolderOpener.open(listOfFiles[0].getParent(), "file=" + fs.getFileNamesRegex());
                } else if (!file_string.equals("")) {
                    temp_img = new Opener().openImage(file_string);
                } else {
                    temp_img = new FolderOpener().openFolder(source_dir);
                }
                imageData = ImageJFunctions.wrapReal(temp_img);
                ImageJFunctions.show(imageData);
                int current_stack_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));
                total_size += current_stack_size;
                System.out.println("Loaded opened image with " + current_stack_size + " slices with size " + total_disk_size + " as normal stack");
            }  else {
                IJ.showStatus("Creating Virtualstack(s)");
                int Stack_no = 0;
                for (int i = 0; i < listOfFiles.length; i++) {
                    if (listOfFiles[i].isFile() && listOfFiles[i].getName().endsWith(".tif")) {

                        vstacks.add(IJ.openVirtual(listOfFiles[i].getPath()).getStack());
                        int current_stack_size = vstacks.get(Stack_no).size();
                        slice_intervals.add(current_stack_size + total_size);
                        total_size += current_stack_size;
                        Stack_no++;
                        System.out.println(i + ", " + listOfFiles[i].getPath() + ", " + current_stack_size + " slices as virtual stack");
                    }
                }
                if(!all_fits && !save_data) {
                    IJ.showMessage("File is too large to not be cached to disk.");
                    save_data = true;
                }
                slice_height = vstacks.get(0).getHeight();
                slice_width = vstacks.get(0).getWidth();
                bit_depth = vstacks.get(0).getBitDepth(); // bitdepth

                System.out.println("Loaded " + total_size + " slices as virtual stack with size " +String.format("%.3f",  ((double)total_disk_size)/(double)(1024*1024*1024)) + " GB");

            }
        } else {
            imageData = ImageJFunctions.wrapReal(imp);
            total_disk_size = (long) imp.getSizeInBytes();
            bit_depth = imageData.firstElement().getBitsPerPixel();

            total_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));

            all_fits = true;

            System.out.println("Loaded already opened image with " + total_size + " slices with size " + total_disk_size + " as normal stack");
        }

        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }

        if (bit_depth == 0) bit_depth = 16;
        else if (bit_depth <= 8) bit_depth = 8;
        else if (bit_depth <= 16) bit_depth = 16;
        else if (bit_depth <= 32) bit_depth = 32;
        else IJ.error("Bitdepth not Supported");


        if(end == 0) end = total_size;
        if(end > total_size) end = total_size;
        if(window > total_size) window = total_size;

        if(save_data){
            target_dir = target_dir.replace('\\', '/');
            if (!target_dir.endsWith("/")) target_dir += "/";
        }
        
        System.gc();
        
        return DOES_8G + DOES_16 + DOES_32 + NO_IMAGE_REQUIRED + NO_UNDO;
    }


    @Override
    public void run(){
        ImagePlus openImage = WindowManager.getCurrentImage();
        if (openImage == null){
            if(setup("", null) != DONE){
                run(null);
            }
        } else {
            if(setup("", openImage) != DONE) {
                run(openImage.getProcessor());
            }
        }
    }

    @Override
    public void run(ImageProcessor ip) {
        long startTime = System.nanoTime();
        long savingTime = 0;


        if(!all_fits) {
            if (window % 2 == 0) window++;

            int slice_size = (slice_height * slice_width * bit_depth)/8;
            int slices_that_fit = min(min((int)(max_bytes/slice_size/ratio), end), total_size);

            ArrayList<int[]> brackets  = new ArrayList<>();


            int slices_left = total_size;
            int lower_end = start;

            while(slices_left > 0){
                slices_left -= slices_that_fit;

                brackets.add(new int[]{lower_end, min(slices_that_fit + lower_end, end)});
                lower_end = min(slices_that_fit + lower_end, end);
            }

            ImageStack temp_temp_stack;
            for(int k = 0; k < brackets.size(); k++) {
                int[] t = brackets.get(k);

                temp_temp_stack = new ImageStack(slice_width, slice_height);
                int s = t[0] == start ? start : t[0] - window/2;
                int e = t[1] == end ? end : t[1] + window/2;

                int temp_index;
                int temp_prev_sizes = 0;

                for(temp_index = 0; vstacks.get(temp_index).size() + temp_prev_sizes < s; temp_index++) temp_prev_sizes += vstacks.get(temp_index).size();

                for(int i = s; i <= e; i++ ) {
                    if(i > slice_intervals.get(temp_index)){
                        temp_prev_sizes += vstacks.get(temp_index).size();
                        temp_index++;
                    }
                    temp_temp_stack.addSlice("" + i, vstacks.get(temp_index).getProcessor(i - temp_prev_sizes));
                }

                System.out.println("Loaded from slice " +  s + " till slice " + e);
                ImagePlus temp_imp = new ImagePlus("", temp_temp_stack);


                Img<T> temp_imglib = ImageJFunctions.wrapReal(temp_imp);

                TemporalMedian.main(temp_imglib, window);

                ImageStack final_stack = new ImageStack(slice_width, slice_height);

                for(int j = t[0] == start ? 1 : window/2 + 1; j <= (t[1] == end ? temp_temp_stack.size() : temp_temp_stack.size() - window/2 - 1); j++){
                    final_stack.addSlice(temp_temp_stack.getProcessor(j));
                }

                long intertime = System.nanoTime();
                saveImagePlus(target_dir + "\\part_" + (k + 1)+ ".tif", new ImagePlus("", final_stack));
                savingTime += (System.nanoTime() - intertime);

                System.gc();
            }

            for(int k = 0; k < brackets.size(); k++) {
                IJ.openVirtual(target_dir + "\\part" + k + ".tif").show();
                IJ.run("Enhance Contrast", "saturated=0.0");
            }

        } else {
            if (window % 2 == 0) window++;

            RandomAccessibleInterval<T> data = Views.offsetInterval(imageData, new long[] {0, 0, start - 1}, new long[] {imageData.dimension(0), imageData.dimension(1) , end});

            TemporalMedian.main(data, window);
            IJ.run("Revert");
            if (save_data && !saveImagePlus(target_dir + "\\Median_corrected.tif", ImageJFunctions.wrap(data, "Median_Corrected"))){
                IJ.error("Failed to write to:" + target_dir + "\\Median_corrected.tif");
                System.exit(0);
            }
            IJ.run("Enhance Contrast", "saturated=0.0");
        }


        //Have to run this since otherwise the data will not be visible (does not change the data)

        long stopTime = System.nanoTime() - startTime;
        double spendTime = (double)stopTime/1000000000;
        double savedTime = (double)savingTime/1000000000;
        System.out.println("Script took " + String.format("%.3f", spendTime) + " s");
        System.out.println("Saving took " + String.format("%.3f", savedTime) + " s");
        System.out.println("Processed " + (end - start + 1) + " frames at " +  String.format("%.1f", (total_disk_size/(1024*1024)/spendTime))+ " MB/s");

        // 01/10
        // now supports all bitdepths
        // saving will make it much longer
        // 1764.552s(30m) on 25 GB file 15.1 MB/s
        // 31.532s on 1.5k large frame virtual 23.6 MB/s
        // 61.272s on 20k virtual 2.6 MB/s
        // 1.819s on 400 virtual 1.6 MB/s
        // 8.324 on 1.5k large frame 32b 171.8 MB/s
        // 7.275s on 1.5k large frame normal 102.3 MB/s
        // 1.453s on 20k normal 108.8 MB/s
        // 0.185s on 400 normal 16.2 MB/s

        // 03/10
        // deprecated gpu implementation
        // 728.586s (12m) on HDD on 25GB file 34.9 MB/s
        // 120s (2m) on HDD on 4GB
        // 7.764s on 1.5k large frame 32b 184.2 MB/s
        // 7.275s on 1.5k large frame normal 102.3 MB/s
        // 1.453s on 20k normal 108.8 MB/s
        // 0.144s on 400 normal 20.9 MB/s

    }

    @Override
    public int showDialog(ImagePlus imagePlus, String s, PlugInFilterRunner plugInFilterRunner) {
        return 0;
    }

    @Override
    public void setNPasses(int i) {

    }



    public static void main(String[] args) {

        ImageJ ij_instance = new ImageJ();
        //ImagePlus imp = IJ.openImage("C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\32btest.tif");
        //imp.show();
        IJ.runPlugIn(FTM2.class.getName(), "");

	
    }




}