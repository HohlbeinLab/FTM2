package com.wurgobes.imagej;

import fiji.util.gui.GenericDialogPlus;

import ij.*;
import ij.plugin.*;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.*;
import ij.io.FileSaver;
import ij.WindowManager;


import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum;
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


@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2", label="FTM2", priority = Priority.VERY_HIGH)
//public class FTM2 implements ExtendedPlugInFilter {
public class FTM2 implements ExtendedPlugInFilter, Command {

    //@Parameter
    public static String sourceDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release

    //@Parameter
    public static String outputDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release

    public static int window = 50;
    public static int start = 1;
    public static int end = 0;

    //@Parameter
    private String target_dir;

    //@Parameter
    private final ArrayList<Integer> slice_intervals = new ArrayList<>();

    //@Parameter
    private int total_size = 0;

    //@Parameter 
    private final ArrayList<ImageStack> vstacks = new ArrayList<>();
    
    private int dimension;
    
    private int slice_height;
    private int slice_width;


    private boolean all_fits = false;

    private long total_disk_size = 0;
    
    private final long max_bytes = Runtime.getRuntime().maxMemory();

    private int bit_depth;
    private String bit_size_string;

    
    public boolean saveShortPixels(final String path, ImageProcessor impP){
        ImagePlus tmp = new ImagePlus("", impP);
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

        boolean pre_loaded_image = imp != null;

        GenericDialogPlus gd = new GenericDialogPlus("Register Virtual Stack");

        gd.addDirectoryField("Source directory", sourceDirectory, 50);
        gd.addDirectoryField("Output directory", outputDirectory, 50);
        gd.addNumericField("Window size", window, 0);
        gd.addNumericField("Begin", start, 0);
        gd.addNumericField("End (0 for all)", end, 0);
        gd.addCheckbox("Use open image?", pre_loaded_image);

        gd.showDialog();

        // Exit when canceled
        if (gd.wasCanceled()) 
                return DONE;

        sourceDirectory = gd.getNextString();
        outputDirectory = gd.getNextString();
        window = (int)gd.getNextNumber();
        start = (int)gd.getNextNumber();
        end = (int)gd.getNextNumber();
        pre_loaded_image = gd.getNextBoolean();


        //@Parameter
        String source_dir = sourceDirectory;
        target_dir = outputDirectory;


        if (!pre_loaded_image && null == source_dir) {
            IJ.error("Error: No source directory was provided.");
            return DONE;
        }
        if (null == target_dir) {
            IJ.error("Error: No output directory was provided.");
            return DONE;
        }

        if(!pre_loaded_image){
            source_dir = source_dir.replace('\\', '/');
            if (!source_dir.endsWith("/")) source_dir += "/";
        }

        target_dir = target_dir.replace('\\', '/');
        if (!target_dir.endsWith("/")) target_dir += "/";




        if(!pre_loaded_image && !(new File(source_dir)).exists()){
            IJ.error("Error: source directory " + source_dir + " does not exist.");
            return DONE;
        }


        if (!(new File(target_dir)).exists()){
            if(!new File(target_dir).mkdir()) {
                IJ.error("Error: Failed to create target directory " + target_dir);
            }
        }

        int ratio = 3;
        all_fits = total_disk_size < max_bytes/ ratio;
        //all_fits = false;
        if(!pre_loaded_image){
            File[] listOfFiles = new File(source_dir).listFiles();
            assert listOfFiles != null;


            for(File file: listOfFiles){
                total_disk_size += file.length();
            }
            if(all_fits){ //All data can fit into memory at once
                IJ.showStatus("Creating stacks");
                vstacks.add(new FolderOpener().openFolder(source_dir).getStack());
                int current_stack_size = vstacks.get(0).size();
                slice_intervals.add(current_stack_size + total_size);
                total_size += current_stack_size;
                System.out.println(source_dir + " with " + current_stack_size + " slices with size " + total_disk_size + " as normal stack");
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
                        System.out.println(i + ", " + listOfFiles[i].getPath() + ", " + current_stack_size + " slices as virtual stack");
                    }
                }
            }
        } else {
            imp = WindowManager.getCurrentImage();
            vstacks.add(imp.getStack());
            int current_stack_size = vstacks.get(0).size();
            slice_intervals.add(current_stack_size + total_size);
            total_size += current_stack_size;
            System.out.println(imp.getTitle() + " with " + current_stack_size+ " slices with size " + total_disk_size + " as normal stack");
        }




        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }
        
        slice_height = vstacks.get(0).getHeight();
        slice_width = vstacks.get(0).getWidth();
        dimension = slice_width * slice_height; //Amount of pixels per image
        bit_depth = vstacks.get(0).getBitDepth(); // bitdepth

        if (bit_depth == 0) {bit_depth = 16; bit_size_string = "Short";}
        else if (bit_depth <= 8) {bit_depth = 8; bit_size_string = "Byte";}
        else if (bit_depth <= 16) {bit_depth = 16; bit_size_string = "Short";}
        else if (bit_depth <= 32) {bit_depth = 32; bit_size_string = "Int";}
        else IJ.error("this is very wrong");


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
        //OtherRun();
    }

    @Override
    public void run(ImageProcessor ip) {
        //This will first be getting a initial implementation to ensure it works
        //If not yet implemented then, it will be ensured files>20GB will work without being loaded into memory
        
        
        /*
        tenet is to access each slice as few times as possible
        
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


        final VirtualStack final_virtual_stack = new VirtualStack(slice_width, slice_height, null, target_dir);
        final_virtual_stack.setBitDepth(bit_depth);


        int start_window = start + window / 2;
        int end_window = end - window / 2;

        int s, e;
        int stack_index;
        int prev_stack_sizes = 0;
        int frameoffset = 0;

        ColorModel cm = vstacks.get(0).getColorModel();

        for(stack_index = 0; vstacks.get(stack_index).size() + prev_stack_sizes < start; stack_index++) prev_stack_sizes += vstacks.get(stack_index).size();
        ImageStack stack = vstacks.get(stack_index);



        long loopstart = System.nanoTime();




        CLIJ2 clij2 = CLIJ2.getInstance();

        ClearCLBuffer temp = clij2.create(new long[]{slice_width, slice_height}, NativeTypeEnum.valueOf(bit_size_string));
        ClearCLBuffer output = clij2.create(temp);
        ClearCLBuffer output_stack = clij2.create(new long[]{slice_width, slice_height, total_size}, NativeTypeEnum.valueOf(bit_size_string));


        ImageStack temp_stack = new ImageStack(stack.getWidth(), stack.getHeight());
        for(int k = start; k <= start + window; k++) {
            if (k > slice_intervals.get(stack_index)) {
                prev_stack_sizes += stack.size();
                stack_index++;
                stack = vstacks.get(stack_index);
            }
            temp_stack.addSlice("", new ShortProcessor(slice_width, slice_height, (short[]) stack.getPixels(k - prev_stack_sizes), cm).duplicate());
        }
        ImagePlus temp_image = new ImagePlus("temp", temp_stack);



        for(int i = start; i <= end; i++){

            IJ.showStatus("Frame " + i+ "/" + total_size);
            IJ.showProgress(i, total_size);

            markedTime = System.nanoTime();

            if(i < end - window && i + window > slice_intervals.get(stack_index)){
                prev_stack_sizes += stack.size();
                stack_index++;
                stack = vstacks.get(stack_index);
            }

            ClearCLBuffer current_frame_CL = clij2.push(new ImagePlus("", temp_image.getStack().getProcessor((i - 1) % window + 1)));

            if(i == start) {
                ClearCLBuffer input = clij2.push(temp_image);
                clij2.medianZProjection(input, temp);
            } else if(i >= start_window && i <= end_window) {
                temp_stack.setProcessor(stack.getProcessor(frameoffset + window - prev_stack_sizes), (frameoffset % window + 1));

                frameoffset++;
                temp_image.setStack(temp_stack);

                ClearCLBuffer input = clij2.push(temp_image);
                clij2.medianZProjection(input, temp);

                input.close();
            }

            loadingTime += System.nanoTime() - markedTime;
            markedTime = System.nanoTime();


            clij2.subtractImages(current_frame_CL, temp, output);

            current_frame_CL.close();

            medianTime += System.nanoTime() - markedTime;

            markedTime = System.nanoTime();

            if(!all_fits){
                ImageProcessor result = clij2.pull(output).getProcessor();
                String save_path = target_dir + "\\slice" + i + ".tif";
                if(!saveShortPixels(save_path, result)){
                    IJ.error("Failed to write to:" + save_path);
                    System.exit(0);
                }
                final_virtual_stack.addSlice("slice" + i + ".tif");
            } else {
                clij2.copySlice(output, output_stack, i - 1);
            }



            if (i%1000 == 0) System.gc();
            savingTime = System.nanoTime() - markedTime;

        }
        ImagePlus test = clij2.pull(output_stack);
        test.setTitle("normal");

        output_stack.close();
        temp.close();
        output.close();
        clij2.clear();


        long loopend = System.nanoTime() - loopstart;
        long stopTime = System.nanoTime() - startTime;
        if(!all_fits){
            new ImagePlus("virtual", final_virtual_stack).show(); //Displaying the final stack
        } else {
            //new ImagePlus("normal", final_normal_stack).show(); //Displaying the final stack
            test.show();
        }

        IJ.run("Enhance Contrast", "saturated=0.0");


        double spendTime = (double)stopTime/1000000000;
        System.out.println("Script took " + String.format("%.3f",spendTime) + " s");
        System.out.println("Processed " + (end - start + 1) + " frames at " +  String.format("%.1f", (double)(total_disk_size/(1000*1000)/spendTime))+ " MB/s");
        System.out.println("GPU took " + String.format("%.3f", (double)loadingTime/1000000000) + " s (" + String.format("%.2f",100* (double)loadingTime/stopTime) + "% of total)");
        System.out.println("GPU took " + String.format("%.3f", (double)medianTime/1000000000) + " s (" + String.format("%.2f",100* (double)medianTime/stopTime) + "% of total)");
        System.out.println("Saving and garbage day took " + String.format("%.3f", (double)savingTime/1000000000) + " s (" + String.format("%.2f", 100*(double)savingTime/stopTime) + "% of total)");
        System.out.println("Extra Loop Stuff took " + String.format("%.3f", (double)(loopend-savingTime-applicationTime-medianTime-loadingTime)/1000000000) + " s (" + String.format("%.2f", 100*(double)(loopend-savingTime-applicationTime-medianTime-loadingTime)/stopTime) + "% of total)");

        // other script
        // super small normal 0.45
        // super small virtual 1.250
        // 20k stack normal: 3.18s
        // 20k stack virtual 56s

        // 18/09
        // implemented multithreading and different median
        // 47s on 1.5k large frame virtual 7979.5  kpxs
        // 33.2s on 20k virtual comparison 2410.1 kpxs
        // 0.95s on 400 virtual comparison 1686.7 kpxs
        // 50s on 1.5k large frame normal 7495.4 kpxs
        // 26.8s on 20k normal comparison 2987.2 kpxs
        // 0.6s on 400 normal comparison 2384.9 kpxs

        // 21/09
        // initial gpu implementation with builtins
        // 37.645s on 1.5k large frame normal 9952.4 kpxs
        // 18.715s on 20k normal comparison 4273.7 kpxs
        // 1.175s on 400 normal comparison  1361.7  kpxs

        // 22/09
        // slightly different gpu implementation for smaller stacks
        // 45.824s on 1.5k large frame virtual 17.0 MB/s
        // 48.023s on 20k virtual 3.5 MB/s
        // 1.76s on 400 virtual 1.7  MB/s
        // 33.780 on 1.5k large frame normal 23.1 MB/s
        // 18.091s on 20k normal 9.2 MB/s
        // 1.193s on 400 normal 2.5 MB/s
    }


    public static void main(String[] args) {

        final ImageJ IJ_Instance = new ImageJ();
        //ImagePlus imp = IJ.openImage("C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder\\stack_small1.tif");
        //imp.show();
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