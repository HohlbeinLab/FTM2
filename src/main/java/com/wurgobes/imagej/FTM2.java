package com.wurgobes.imagej;

import fiji.util.gui.GenericDialogPlus;

import ij.*;
import ij.plugin.*;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.*;


import org.scijava.command.Command;

import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;


import org.scijava.plugin.*;
import org.scijava.Priority;


import java.io.File;
import java.util.*; 
import jdk.nashorn.internal.ir.debug.ObjectSizeCalculator;



@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2", label="FTM2", priority = Priority.VERY_HIGH)
public class FTM2 implements ExtendedPlugInFilter, Command {
    

    //@Parameter
    private ImageStack stack;


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
    private ArrayList<Integer> slice_intervals = new ArrayList<Integer>();

    //@Parameter
    private Integer total_size = 0;

    //@Parameter 
    private ArrayList<ImageStack> vstacks = new ArrayList<ImageStack>();
    
    private Integer dimension;
    
    private Integer slice_size;
    
    private final long max_bytes = Runtime.getRuntime().maxMemory();

    public Integer bit_depth;
    
    public long getFreeMemory(boolean am_i_the_garbage_man){
        if (am_i_the_garbage_man){
            System.gc();
        }
        return Runtime.getRuntime().freeMemory();
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




        if( (new File( source_dir )).exists() == false ){
            IJ.error("Error: source directory " + source_dir + " does not exist.");
            return DONE;
        }


        if ((new File( target_dir )).exists() == false){
            if(!new File(target_dir).mkdir()) {
                IJ.error("Error: Failed to create target directory " + target_dir);
            }
        }

        File[] listOfFiles = new File(source_dir).listFiles();               
        

        IJ.showStatus("Creating Virtualstack(s)");
        for (int i = 0; i < listOfFiles.length; i++) {                       
            if (listOfFiles[i].isFile() && listOfFiles[i].getName().contains("tif")) {

                vstacks.add(IJ.openVirtual(listOfFiles[i].getPath()).getStack());
                Integer current_stack_size = vstacks.get(i).size();
                slice_intervals.add(current_stack_size + total_size);
                total_size += current_stack_size;
                
                System.out.print(Integer.toString(i) + ", " + listOfFiles[i].getPath() + ", " + Integer.toString(current_stack_size) + "\n");
            } else {
                IJ.error("Error: File is not file.");
            }
        }

        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }
        
        dimension = vstacks.get(0).getWidth() * vstacks.get(0).getHeight(); //Amount of pixels per image
        bit_depth = vstacks.get(0).getBitDepth(); // bitdepth
        slice_size = dimension * bit_depth; //Bits per slice
        
        
        if(end == 0) end = total_size;
        if(window > total_size) window = total_size;
        
        System.gc();
        
        return DOES_8G + DOES_16 + DOES_32 + NO_IMAGE_REQUIRED + NO_UNDO;
    }

    @Override
    public void run(){       
        ImagePlus Dummy = new ImagePlus();
        setup("", Dummy);
        run(Dummy.getProcessor());
    }
    
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
        
        VirtualStack temp_stack = new VirtualStack(vstacks.get(0).getWidth(), vstacks.get(0).getHeight(), total_size);
        short[] pixels = new short[dimension];
        short[] new_pixels = new short[dimension];
        Integer stack_index = 0;
        Integer prev_stack_size = 0;
        
        System.out.println(slice_size); 
        System.out.println(bit_depth);
        
        Integer slices_that_fit = (int)(getFreeMemory(true)/slice_size)/(2/8);
        if (slices_that_fit > total_size) slices_that_fit = total_size;
        
        short[][] v_pixels = new short[dimension][slices_that_fit]; 
        
        Integer[] loaded_range = {1, slices_that_fit};
        
        
     
        System.out.println(total_size);
        System.out.println(slices_that_fit);

        //Populate array
        stack = vstacks.get(0);
        for(int i = start; i < slices_that_fit; i++){
            if(i > slice_intervals.get(stack_index)){
                prev_stack_size += stack.size();
                stack_index += 1;
                stack = vstacks.get(stack_index);
            }
            pixels = (short[])stack.getPixels(i - prev_stack_size);
            for (int j=0; j<dimension; j++){
                v_pixels[j][i] = pixels[j];
            }
        }

        long stopTime = (System.nanoTime()- startTime);
        IJ.showMessage("Script took " + String.format("%.3f", (double)stopTime/1000000000) + " s");
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