package com.wurgobes.imagej;
/* Fast Temporal Median filter
(c) 2020 Martijn Gobes, Netherlands Cancer Institute.
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
import ij.plugin.*;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.*;
import ij.io.FileSaver;
import ij.WindowManager;
import ij.gui.YesNoCancelDialog;

import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.view.Views;

import org.scijava.plugin.*;
import org.scijava.Priority;
import org.scijava.command.Command;

import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.*;
import javax.swing.*;
import java.awt.event.ActionListener;



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
@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2")
//T extends RealType so this should support any image that implements this. 8b, 16b, 32b are confirmed to work
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
    private String bit_size_string;

    boolean force_gpu = false;

    private Img<T> imageData;

    public boolean saveImagePlus(final String path, ImagePlus impP){
        //Saves an ImagePlus Object as a tiff at the provided path, returns true if succeeded, false if not
        try {
            return new FileSaver(impP).saveAsTiff(path);
        } catch (Exception e) {
            return false;
        }
    }

    /*
    Setup sets up a variety of variables like bitdepth and
    dimension as well as loading the imagedata requested by the user into the type required.

    arg will always be ""
    imp will contain the already opened image, if one exists, otherwise it is null
     */
    @Override
    public int setup(String arg, ImagePlus imp) {

        //set the flag if we have an image already opened (and thus loaded)
        boolean pre_loaded_image = imp != null;

        //Default strings for the source and output directories
        String source_dir="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release
        target_dir="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release

        //Custom class that allows a button to select multiple files using a JFilechooser as GenericDialog doesn't suppor this
        MultiFileSelect fs = new MultiFileSelect();

        //Create the setup dialogue and its components
        GenericDialogPlus gd = new GenericDialogPlus("Settings");
        gd.addMessage("Temporal Median Filter");
        gd.addDirectoryField("Source directory", source_dir, 50);
        gd.addButton("Select Files", fs); //Custom button that allows for creating and deleting a list of files
        gd.addToSameRow();
        gd.addButton("Clear Selected Files", fs);
        gd.addDirectoryField("Output directory", target_dir, 50);
        gd.addNumericField("Window size", window, 0);
        gd.addNumericField("Begin", start, 0);
        gd.addNumericField("End (0 for all)", end, 0);
        gd.addCheckbox("Use open image?", pre_loaded_image);
        gd.addCheckbox("Force GPU?", force_gpu);

        //Show the dialogue
        gd.showDialog();

        // Exit when canceled
        if (gd.wasCanceled())
            return DONE;

        //Retrieve all the information from the dialogue,
        source_dir = gd.getNextString();
        File[] selected_files = fs.getFiles();
        target_dir = gd.getNextString();
        window = (int)gd.getNextNumber();
        start = (int)gd.getNextNumber();
        end = (int)gd.getNextNumber();
        pre_loaded_image = gd.getNextBoolean();
        force_gpu = gd.getNextBoolean();



        if (!pre_loaded_image && null == source_dir && selected_files == null) {
            IJ.error("Error: No source directory was provided.");
            return DONE;
        }
        if (null == target_dir) {
            IJ.error("Error: No output directory was provided.");
            return DONE;
        }

        if(!pre_loaded_image && selected_files == null){
            source_dir = source_dir.replace('\\', '/');
            if (!source_dir.endsWith("/")) source_dir += "/";
        }

        target_dir = target_dir.replace('\\', '/');
        if (!target_dir.endsWith("/")) target_dir += "/";


        if(selected_files == null && !pre_loaded_image && !(new File(source_dir)).exists()){
            IJ.error("Error: source directory " + source_dir + " does not exist.");
            return DONE;
        }

        if (!(new File(target_dir)).exists()){
            if(!new File(target_dir).mkdir()) {
                IJ.error("Error: Failed to create target directory " + target_dir);
            }
        }

        int dimension;
        if(!pre_loaded_image){

            File[] listOfFiles;

            if (selected_files == null){
                listOfFiles = new File(source_dir).listFiles();
            } else {
                listOfFiles = selected_files;
            }

            assert listOfFiles != null;


            for(File file: listOfFiles){
                total_disk_size += file.length();

            }
            all_fits = total_disk_size < max_bytes / 1.5;




            if(all_fits && !force_gpu){ //All data can fit into memory at once
                IJ.showStatus("Creating stacks");

                ImagePlus temp_img;
                if (selected_files != null) {
                    temp_img = FolderOpener.open(listOfFiles[0].getParent(), "file=" + fs.getFileNamesRegex());

                } else {
                    temp_img = new FolderOpener().openFolder(source_dir);
                }
                imageData = ImageJFunctions.wrapReal(temp_img);

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

                slice_height = vstacks.get(0).getHeight();
                slice_width = vstacks.get(0).getWidth();
                bit_depth = vstacks.get(0).getBitDepth(); // bitdepth

            }
        } else {
            int current_stack_size;
            assert imp != null;

            if(force_gpu && imp.getBitDepth() != 32){
                vstacks.add(imp.getStack());
                current_stack_size = vstacks.get(0).size();
                slice_intervals.add(current_stack_size + total_size);
                total_size += current_stack_size;

                slice_height = imp.getHeight();
                slice_width = imp.getWidth();
                dimension = slice_width * slice_height; //Amount of pixels per image
                bit_depth = imp.getBitDepth(); // bitdepth
                total_disk_size = current_stack_size * dimension * bit_depth / 8;
            } else {
                imageData = ImageJFunctions.wrapReal(imp);
                total_disk_size = (long) imp.getSizeInBytes();
                bit_depth = imageData.firstElement().getBitsPerPixel();

                current_stack_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));
            }

            all_fits = true;


            total_size += current_stack_size;
            System.out.println("Loaded already opened image with " + current_stack_size+ " slices with size " + total_disk_size + " as normal stack");
        }
        

        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }
        

        if (bit_depth == 0) {bit_depth = 16; bit_size_string = "Short";}
        else if (bit_depth <= 8) {bit_depth = 8; bit_size_string = "Byte";}
        else if (bit_depth <= 16) {bit_depth = 16; bit_size_string = "Short";}
        else if (bit_depth <= 32) {bit_depth = 32; bit_size_string = "Int";}
        else IJ.error("this is very wrong");

        if(bit_depth == 32 && !all_fits) IJ.error("currently does not support 32 bit");
        if(bit_depth == 32 && force_gpu) IJ.showMessage("Currently does not support 32-bit on GPU, reverting to CPU"); force_gpu = false;

        if(end == 0) end = total_size;
        if(window > total_size) window = total_size;
        
        System.gc();
        
        return DOES_8G + DOES_16 + DOES_32 + NO_IMAGE_REQUIRED + NO_UNDO;
    }



    @Override
    public void run(){
        ImagePlus openImage = WindowManager.getCurrentImage();
        if (openImage == null){
            System.out.println("found no image");
            setup("", null);
            run(null);
        } else {
            System.out.println("found image");
            setup("", openImage);
            run(openImage.getProcessor());
        }
    }

    @Override
    public void run(ImageProcessor ip) {
        long startTime = System.nanoTime();



        if(!all_fits | force_gpu) {
            int start_window = start + window / 2;
            int end_window = end - window / 2;

            int stack_index;
            int prev_stack_sizes = 0;
            int frameoffset = 0;


            final VirtualStack final_virtual_stack = new VirtualStack(slice_width, slice_height, vstacks.get(0).getColorModel(), target_dir);
            final_virtual_stack.setBitDepth(bit_depth);

            for(stack_index = 0; vstacks.get(stack_index).size() + prev_stack_sizes < start; stack_index++) prev_stack_sizes += vstacks.get(stack_index).size();
            ImageStack stack = vstacks.get(stack_index);

            CLIJ2 clij2 = CLIJ2.getInstance();

            ClearCLBuffer temp = clij2.create(new long[]{slice_width, slice_height}, NativeTypeEnum.valueOf(bit_size_string));
            ClearCLBuffer output = clij2.create(temp);

            ImageStack temp_stack = new ImageStack(stack.getWidth(), stack.getHeight());

            for(int k = start; k <= start + window; k++) {
                if (k > slice_intervals.get(stack_index)) {
                    prev_stack_sizes += stack.size();
                    stack_index++;
                    stack = vstacks.get(stack_index);
                }
                temp_stack.addSlice("", stack.getProcessor(k - prev_stack_sizes));
            }
            ImagePlus temp_image = new ImagePlus("temp", temp_stack);

            for (int i = start; i <= end; i++) {

                IJ.showStatus("Frame " + i + "/" + total_size);
                IJ.showProgress(i, total_size);


                if (i < end - window && i + window / 2 > slice_intervals.get(stack_index)) {
                    prev_stack_sizes += stack.size();
                    stack_index++;
                    stack = vstacks.get(stack_index);
                }

                ClearCLBuffer current_frame_CL = clij2.push(new ImagePlus("", temp_stack.getProcessor((i - 1) % window + 1)));

                if (i == start) {
                    ClearCLBuffer input = clij2.push(temp_image);
                    clij2.medianZProjection(input, temp);
                } else if (i >= start_window && i <= end_window) {

                    temp_stack.setProcessor(stack.getProcessor(frameoffset + window - prev_stack_sizes + 1), (frameoffset % window + 1));

                    frameoffset++;
                    temp_image.setStack(temp_stack);

                    ClearCLBuffer input = clij2.push(temp_image);
                    clij2.medianZProjection(input, temp);

                    input.close();
                }

                clij2.subtractImages(current_frame_CL, temp, output);

                current_frame_CL.close();


                ImagePlus result = clij2.pull(output);
                String save_path = target_dir + "\\slice" + i + ".tif";
                if (!saveImagePlus(save_path, result)) {
                    IJ.error("Failed to write to:" + save_path);
                    System.exit(0);
                }
                final_virtual_stack.addSlice("slice" + i + ".tif");

                if (i % 1000 == 0) System.gc();

            }

            new ImagePlus("virtual", final_virtual_stack).show(); //Displaying the final stack

            //Cleanup of clij2 data

            temp.close();
            output.close();
            clij2.clear();
        } else {
            if (window % 2 == 0) window++;

            RandomAccessibleInterval< T > data = Views.offsetInterval(imageData, new long[] {0, 0, start - 1}, new long[] {imageData.dimension(0), imageData.dimension(1) , end});

            TemporalMedian.main(data, window);
            ImageJFunctions.show(data);
            saveImagePlus(target_dir + "\\Median_corrected.tif", ImageJFunctions.wrap(data, "Median_Corrected"));
        }


        //Have to run this since otherwise the data will not be visible (does not change the data)
        IJ.run("Enhance Contrast", "saturated=0.0");


        long stopTime = System.nanoTime() - startTime;
        double spendTime = (double)stopTime/1000000000;
        System.out.println("Script took " + String.format("%.3f",spendTime) + " s");
        System.out.println("Processed " + (end - start + 1) + " frames at " +  String.format("%.1f", (total_disk_size/(1024*1024)/spendTime))+ " MB/s");

        // other script
        // 20k stack normal: 3.18s
        // 20k stack virtual 56s

        // 22/09
        // combined 2 different methods depending on memory
        // 1764.552s(30m) on 25 GB file 15.1 MB/s
        // 45.824s on 1.5k large frame virtual 17.0 MB/s
        // 48.023s on 20k virtual 3.5 MB/s
        // 1.76s on 400 virtual 1.7  MB/s
        // 6.255s on 1.5k large frame normal 124.9 MB/s
        // 1.231s on 20k normal 134.8 MB/s
        // 0.151s on 400 normal 14.5 MB/s
    }


    public static void main(String[] args) {

        ImagePlus imp = IJ.openImage("C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\32btest2.tif");
        imp.show();
        IJ.runPlugIn(FTM2.class.getName(), "");

	
    }

    @Override
    public int showDialog(ImagePlus ip, String string, PlugInFilterRunner pifr) {        
        return 1;
    }

    @Override
    public void setNPasses(int i) {
        //thank you very cool
    }

}