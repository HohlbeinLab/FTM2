package com.wurgobes.imagej;

import fiji.util.gui.GenericDialogPlus;

import ij.*;
import ij.plugin.*;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.*;
import ij.io.FileSaver;

import org.scijava.plugin.*;
import org.scijava.Priority;
import org.scijava.command.Command;

import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;


import com.wurgobes.imagej.MiscFunctions;


import java.io.File;
import java.util.*; 
import java.awt.image.ColorModel;

import jdk.nashorn.internal.ir.debug.ObjectSizeCalculator;

import static java.lang.Math.max;
import static java.lang.Math.min;


@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2", label="FTM2", priority = Priority.VERY_HIGH)
public class FTM2 implements ExtendedPlugInFilter {
//public class FTM2 implements ExtendedPlugInFilter, Command {

    //@Parameter
    public static String sourceDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release

    //@Parameter
    public static String outputDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release

    public static Integer window = 50;
    public static Integer start = 1;
    public static Integer end = 0;

    //@Parameter
    private String source_dir;

    //@Parameter
    private String target_dir;

    //@Parameter
    private final ArrayList<Integer> slice_intervals = new ArrayList<Integer>();

    //@Parameter
    private Integer total_size = 0;

    //@Parameter 
    private final ArrayList<ImageStack> vstacks = new ArrayList<ImageStack>();
    
    private Integer dimension;
    
    private Integer slice_height;
    private Integer slice_width;
    
    private Integer slice_size;

    private boolean pre_loaded_image = false;

    private boolean all_fits = false;

    private long total_disk_size = 0;
    
    private final long max_bytes = Runtime.getRuntime().maxMemory();

    private Integer bit_depth;
    
    public long getFreeMemory(boolean am_i_the_garbage_man){
        if (am_i_the_garbage_man){
            System.gc();
        }
        return Runtime.getRuntime().freeMemory();
    }
    
    public boolean saveShortPixels(final String path, short[] pixels, ColorModel cm){
        ImagePlus tmp = new ImagePlus("", new ShortProcessor(slice_width, slice_height, pixels, cm).duplicate());
        try {
                return new FileSaver(tmp).saveAsTiff(path);
        } catch (Exception e) {
                return false;
        }
    }

    @Override
    public int setup(String arg, ImagePlus imp) {
        //TODO
        //Handle closure of the file select more graciously
        //Add an about
        //Hyperstack support?
        //NOTE that the final implementation probably wont have the entire stack loaded into memory as a Imagestack, at most as a virtualstack


        GenericDialogPlus gd = new GenericDialogPlus("Register Virtual Stack");

        gd.addDirectoryField("Source directory", sourceDirectory, 50);
        gd.addDirectoryField("Output directory", outputDirectory, 50);
        gd.addNumericField("Window size", window, 0);
        gd.addNumericField("Begin", start, 0);
        gd.addNumericField("End (0 for all)", end, 0);

        gd.showDialog();

        // Exit when canceled
        if (gd.wasCanceled()) 
                return DONE;

        sourceDirectory = gd.getNextString();
        outputDirectory = gd.getNextString();
        window = (int)gd.getNextNumber();
        start = (int)gd.getNextNumber();
        end = (int)gd.getNextNumber();


        source_dir = sourceDirectory;
        target_dir = outputDirectory;


        if (null == source_dir) {
            IJ.error("Error: No source directory was provided.");
            return DONE;
        }
        if (null == target_dir) {
            IJ.error("Error: No output directory was provided.");
            return DONE;
        }


        source_dir = source_dir.replace('\\', '/');
        if (!source_dir.endsWith("/")) source_dir += "/";

        target_dir = target_dir.replace('\\', '/');
        if (!target_dir.endsWith("/")) target_dir += "/";




        if(!(new File(source_dir)).exists()){
            IJ.error("Error: source directory " + source_dir + " does not exist.");
            return DONE;
        }


        if (!(new File(target_dir)).exists()){
            if(!new File(target_dir).mkdir()) {
                IJ.error("Error: Failed to create target directory " + target_dir);
            }
        }

        if(!pre_loaded_image){
            File[] listOfFiles = new File(source_dir).listFiles();
            assert listOfFiles != null;


            for(File file: listOfFiles){
                total_disk_size += file.length();
            }
            if(total_disk_size < max_bytes/2){ //All data can fit into memory at once
                all_fits = true;
                IJ.showStatus("Creating stacks");
                vstacks.add(new FolderOpener().openFolder(source_dir).getStack());
                int current_stack_size = vstacks.get(0).size();
                slice_intervals.add(current_stack_size + total_size);
                total_size += current_stack_size;
                System.out.print(source_dir + ", " + Integer.toString(current_stack_size) + "\n");
            } else {
                IJ.showStatus("Creating Virtualstack(s)");
                int Stack_no = 0;
                for (int i = 0; i < listOfFiles.length; i++) {
                    if (listOfFiles[i].isFile() && listOfFiles[i].getName().endsWith(".tif")) {

                        vstacks.add(IJ.openVirtual(listOfFiles[i].getPath()).getStack());
                        int current_stack_size = vstacks.get(Stack_no).size();
                        slice_intervals.add(current_stack_size + total_size);
                        total_size += current_stack_size;
                        Stack_no++;
                        System.out.print(Integer.toString(i) + ", " + listOfFiles[i].getPath() + ", " + Integer.toString(current_stack_size) + "\n");
                    }
                }
            }
        }




        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }
        
        slice_height = vstacks.get(0).getHeight();
        slice_width = vstacks.get(0).getWidth();
        dimension = slice_width * slice_height; //Amount of pixels per image
        bit_depth = vstacks.get(0).getBitDepth(); // bitdepth

        if (bit_depth == 0) bit_depth = 16;
        else if (bit_depth <= 8) bit_depth = 8;
        else if (bit_depth <= 16) bit_depth = 16;
        else if (bit_depth <= 32) bit_depth = 32;
        else IJ.error("this is very wrong");



        slice_size = dimension * bit_depth * 8; //bytes per slice
        
        
        if(end == 0) end = total_size;
        if(window > total_size) window = total_size;
        
        System.gc();
        
        return DOES_8G + DOES_16 + DOES_32 + NO_IMAGE_REQUIRED + NO_UNDO;
    }

    /*
    @Override
    public void run(){       
        //ImagePlus Dummy = new ImagePlus();
        //setup("", Dummy);
        //run(Dummy.getProcessor());
        //otherRun();
    }
     */
    @Override
    public void run(ImageProcessor ip) {
        //This will first be getting a initial implementation to ensure it works
        //If not yet implemented then, it will be ensured files>20GB will work without being loaded into memory
        
        
        /*
        tenet is to acces each slice as few times as possible
        
        get available memory
        load half as many slices as fit (x by y times n) (the other half will be taken up by the reordered arrays)
        for the entire loaded stack:
            create vertically sliced arrays (v_pixels) (dimension arrays with n entries)
        for every n entries:
            take a i_window of desired size and take the median of v_pixels with that range and subtract that from the entry n (min 0) 
                this median does not change for the first i_window slices and last i_window slices
            look ahead and back as equally as possible (so from n-window/2 to n+window/2, with only forward and backward for the start and end respectively)   
            when the buffered pixels run out, delete all unneeded data and load as much new data as can fit (to minimise memory writes)
                one can overwrite the no longer needed pixels
            
            per n you reconstruct one slice by getting a single pixel per v_pixels array and putting those back
            
        */
        //slice_size + 16 is the size of one frame when gotten with getPixels(n)
        
        long startTime = System.nanoTime();
        long savingTime = 0;
        long medianTime = 0;
        long loadingTime = 0;
        long applicationTime = 0;
        long markedTime;
        
        ColorModel cm = vstacks.get(0).getColorModel();

        final VirtualStack final_virtual_stack = new VirtualStack(slice_width, slice_height, null, target_dir);
        final ImageStack final_normal_stack = new ImageStack(slice_width, slice_height);

        final_virtual_stack.setBitDepth(bit_depth);
        

        short[] new_pixels = new short[dimension];

        
        int slices_that_fit = (int)(max_bytes/slice_size/2);

        slices_that_fit = all_fits ? total_size : min(min(slices_that_fit, end), total_size);
        //slices_that_fit = 1000;



        short[] medians = new short[dimension];
        short[] temp = new short[window];

        ImageStack stack = vstacks.get(0);
        int[] loaded_range = {0, 0};
        boolean final_images = false;
        boolean final_median_created = false;
        int stack_index = 0;
        int prev_stack_sizes = 0;
        short newval = 0;
        int slice_offset = 0;

        long initialisationTime = System.nanoTime() - startTime;
        long loopstart = System.nanoTime();

        short[][] test_pixels = new short[window][dimension];

        for(int i = 0; i < window; i++){
            System.arraycopy((short[])stack.getPixels(start + i), 0, test_pixels[i], 0, dimension);
        }

        for(int i = start; i <= end; i++){

            IJ.showStatus("Frame " + String.valueOf(i) + "/" + String.valueOf(total_size));
            IJ.showProgress(i, total_size);

            if(i > slice_intervals.get(stack_index)){
                prev_stack_sizes += stack.size();
                stack_index++;
                stack = vstacks.get(stack_index);
            }

            System.arraycopy((short[])stack.getPixels(i - prev_stack_sizes), 0, test_pixels[i%50], 0, dimension);



            markedTime = System.nanoTime();

            if(!final_images) final_images = (i + window/2 > end);


            if (!final_images && i <= window / 2){ //start of images, so any median for the first window frames wont change
                if(i == start){
                    for (int j=0; j<dimension; j++){
                        for(int x = 0; x < window; x++){
                            temp[x] = test_pixels[x][j];
                        }
                        medians[j] = (short)MiscFunctions.getMedian(temp);
                    }
                }
            } else if(final_images) { //end of images, so any median for the last window frames wont change
                if(!final_median_created){
                    for (int j=0; j<dimension; j++){
                        for(int x = 0; x < window; x++){
                            temp[x] = test_pixels[x][j];
                        }
                        medians[j] = (short)MiscFunctions.getMedian(temp);
                    }
                    final_median_created = true;
                }
            } else {
                for (int j=0; j<dimension; j++){
                    for(int x = 0; x < window; x++) {
                        temp[x] = test_pixels[x][j];
                    }
                    medians[j] = (short)MiscFunctions.getMedian(temp);
                }
            }


            medianTime += System.nanoTime() - markedTime;

            markedTime = System.nanoTime();


            for (int j=0; j<dimension; j++){
                newval = (short) (test_pixels[i%50][j] - medians[j]);
                new_pixels[j] = newval < 0 ? 0 : newval;
            }

            new_pixels = new_pixels.clone();
            applicationTime += System.nanoTime() - markedTime;

            markedTime = System.nanoTime();
            if(!all_fits){
                String save_path = target_dir + "\\slice" + Integer.toString(i) + ".tif";
                if(!saveShortPixels(save_path, new_pixels, cm)){
                    IJ.error("Failed to write to:" + save_path);
                    System.exit(0);
                }
                final_virtual_stack.addSlice("slice" + Integer.toString(i) + ".tif");
            } else {
                final_normal_stack.addSlice("slice" + Integer.toString(i), new_pixels);
            }
            savingTime = System.nanoTime() - markedTime;

            if (i%1000 == 0) System.gc();

        }
        long loopend = System.nanoTime() - loopstart;
        long stopTime = System.nanoTime() - startTime;
        if(!all_fits){
            new ImagePlus("virtual", final_virtual_stack).show(); //Displaying the final stack
        } else {
            new ImagePlus("normal", final_normal_stack).show(); //Displaying the final stack
        }


        System.out.println("Script took " + String.format("%.3f", (double)stopTime/1000000000) + " s");
        System.out.println("Processed " + Integer.toString(end - start + 1) + " frames");
        System.out.println("Initialisation took " + String.format("%.3f", (double)initialisationTime/1000000000) + " s (" + String.format("%.2f",100* (double)initialisationTime/stopTime) + "% of total)");
        System.out.println("Loading took " + String.format("%.3f", (double)loadingTime/1000000000) + " s (" + String.format("%.2f",100* (double)loadingTime/stopTime) + "% of total)");
        System.out.println("Median took " + String.format("%.3f", (double)medianTime/1000000000) + " s (" + String.format("%.2f",100* (double)medianTime/stopTime) + "% of total)");
        System.out.println("Loading took " + String.format("%.3f", (double)applicationTime/1000000000) + " s (" + String.format("%.2f",100* (double)applicationTime/stopTime) + "% of total)");
        System.out.println("Saving took " + String.format("%.3f", (double)savingTime/1000000000) + " s (" + String.format("%.2f", 100*(double)savingTime/stopTime) + "% of total)");
        System.out.println("Extra Loop Stuff took " + String.format("%.3f", (double)(loopend-savingTime-applicationTime-medianTime-loadingTime)/1000000000) + " s (" + String.format("%.2f", 100*(double)(loopend-savingTime-applicationTime-medianTime-loadingTime)/stopTime) + "% of total)");
        System.out.println(String.format("%.3f", (double)(stopTime-initialisationTime-loopend)/1000000000) + " s unnacounted for (" + String.format("%.2f", 100*(double)(stopTime-loopend)/stopTime) + "% of total)");
        //7.6 s for old on smaller comparison
        //110 s for new on smaller comparison
        //108 s for smaller memory mode
        //no med large buffer 6s
        //no med small buffer 5s

        // buffer 400 frames: 0.6s
        // no buffer 400 frames: 2s
        // pixel buffer size seems to reallllllly not matter
    }

    public void otherRun(){

        long startTime = System.nanoTime();
        IJ.showStatus("Allocating memory...");
        ImageStack stack = vstacks.get(0);
        ImageStack sub = new ImageStack(stack.getWidth(),stack.getHeight()); //ImageStack to save the filtered images

        int dimension = stack.getWidth()*stack.getHeight(); //ImageJ saves the pixels of the image in an unidimensional array of size width*height
        int colors = 65536; //2^16 (color depth)
        short[] pixels = new short[dimension];
        short[] pixels2 = new short[dimension]; //Arrays to save the pixels that are being processed
        short[] median = new short[dimension]; //Array to save the median pixels
        byte[] aux = new byte[dimension]; //Marks the position of each median pixel in the column of the histogram, starting with 1
        byte[][] hist = new byte[dimension][colors]; //Gray-level histogram

        System.gc();
        for (int k=start; k<=(end-window); k++) //Each passing creates one median frame
        {
            IJ.showStatus("Frame " + String.valueOf(k) + "/" + String.valueOf(end));
            IJ.showProgress(k,end );

            //median = median.clone(); //Cloning the median, or else the changes would overlap the previous median

            if (k==start) //Building the first histogram
            {
                for (int i=1; i<=window; i++) //For each frame inside the window
                {
                    pixels = (short[])(stack.getPixels(i+k-1)); //Save all the pixels of the frame "i+k-1" in "pixels" (starting with 1)
                    for (int j=0; j<dimension; j++) //For each pixel in this frame
                        hist[j][pixels[j]]++; //Add it to the histogram
                }
                for (int i=0; i<dimension; i++) //Calculating the median
                {
                    short count=0, j=-1;
                    while(count<(window/2)) //Counting the histogram, until it reaches the median
                    {
                        j++;
                        count += hist[i][j];
                    }
                    aux[i] = (byte)(count - (int)(Math.ceil(window/2)) + 1);
                    median[i] = j;
                }
            }
            else
            {
                pixels = (short[])(stack.getPixels(k-1)); //Old pixels, remove them from the histogram
                pixels2 = (short[])(stack.getPixels(k+window-1)); //New pixels, add them to the histogram
                for (int i=0; i<dimension; i++) //Calculating the new median
                {
                    hist[i][pixels[i]]--; //Removing old pixel
                    hist[i][pixels2[i]]++; //Adding new pixel
                    if (!(((pixels[i]>median[i]) &&
                            (pixels2[i]>median[i])) ||
                            ((pixels[i]<median[i]) &&
                                    (pixels2[i]<median[i])) ||
                            ((pixels[i]==median[i]) &&
                                    (pixels2[i]==median[i]))))
                    //Add and remove the same pixel, or pixels from the same side, the median doesn't change
                    {
                        int j=median[i];
                        if ((pixels2[i]>median[i]) && (pixels[i]<median[i])) //The median goes right
                        {
                            if (hist[i][median[i]] == aux[i]) //The previous median was the last pixel of its column in the histogram, so it changes
                            {
                                j++;
                                while (hist[i][j] == 0) //Searching for the next pixel
                                    j++;
                                median[i] = (short)(j);
                                aux[i] = 1; //The median is the first pixel of its column
                            }
                            else
                                aux[i]++; //The previous median wasn't the last pixel of its column, so it doesn't change, just need to mark its new position
                        }
                        else if ((pixels[i]>median[i]) && (pixels2[i]<median[i])) //The median goes left
                        {
                            if (aux[i] == 1) //The previous median was the first pixel of its column in the histogram, so it changes
                            {
                                j--;
                                while (hist[i][j] == 0) //Searching for the next pixel
                                    j--;
                                median[i] = (short)(j);
                                aux[i] = hist[i][j]; //The median is the last pixel of its column
                            }
                            else
                                aux[i]--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
                        }
                        else if (pixels2[i]==median[i]) //new pixel = last median
                        {
                            if (pixels[i]<median[i]) //old pixel < last median, the median goes right
                                aux[i]++; //There is at least one pixel above the last median (the one that was just added), so the median doesn't change, just need to mark its new position
                            //else, absolutely nothing changes
                        }
                        else //pixels[i]==median[i], old pixel = last median
                        {
                            if (pixels2[i]>median[i]) //new pixel > last median, the median goes right
                            {
                                if (aux[i] == (hist[i][median[i]]+1)) //The previous median was the last pixel of its column, so it changes
                                {
                                    j++;
                                    while (hist[i][j] == 0) //Searching for the next pixel
                                        j++;
                                    median[i] = (short)(j);
                                    aux[i] = 1; //The median is the first pixel of its column
                                }
                                //else, absolutely nothing changes
                            }
                            else //pixels2[i]<median[i], new pixel < last median, the median goes left
                            {
                                if (aux[i] == 1) //The previous median was the first pixel of its column in the histogram, so it changes
                                {
                                    j--;
                                    while (hist[i][j] == 0) //Searching for the next pixel
                                        j--;
                                    median[i] = (short)(j);
                                    aux[i] = hist[i][j]; //The median is the last pixel of its column
                                }
                                else
                                    aux[i]--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
                            }
                        }
                    }
                }
            }

            //Subtracting the median
            pixels = (short[])(stack.getPixels(k));
            pixels = pixels.clone();
            for (int j=0; j<dimension; j++)
            {
                pixels[j] -= median[j];
                if (pixels[j] < 0)
                    pixels[j] = 0;
            }
            //ip.setPixels(pixels);
            //stack.addSlice("",pixels);
            //stack.deleteSlice(k);
            sub.addSlice("",pixels); //Add the frame to the stack

            if ((k%1000) == 0)
                System.gc(); //Calls the Garbage Collector every 1000 frames
        }
        long stopTime = (System.nanoTime()- startTime);
        IJ.showMessage("Script took " + String.format("%.3f", (double)stopTime/1000000000) + " s");
        new ImagePlus("Med_" , sub).show(); //Displaying the final stack
    }


    public static void main(String[] args) throws Exception {

        final ImageJ IJ_Instance = new ImageJ();

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