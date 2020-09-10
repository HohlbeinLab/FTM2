package com.wurgobes.imagej;


import ij.*;
import fiji.util.gui.GenericDialogPlus;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.*;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.ImageProcessor;

import org.scijava.command.Command;

import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;


import org.scijava.plugin.*;
import org.scijava.Priority;
import org.scijava.table.*;

import java.io.File;
import java.util.*; 


@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2", label="FTM2", priority = Priority.VERY_HIGH)
public class FTM2 implements ExtendedPlugInFilter, Command {
    

    //@Parameter
    private ImagePlus stack;


    //@Parameter
    public static String sourceDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release

    //@Parameter
    public static String outputDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release


    //@Parameter
    private String source_dir;

    //@Parameter
    private String target_dir;

    //@Parameter
    private ArrayList<Integer> slice_intervals = new ArrayList<Integer>();

    //@Parameter
    private Integer total_size = 0;

    //@Parameter 
    private ArrayList<ImagePlus> vstacks = new ArrayList<ImagePlus>();

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

        gd.showDialog();

        // Exit when canceled
        if (gd.wasCanceled()) 
                return DONE;

        sourceDirectory = gd.getNextString();
        outputDirectory = gd.getNextString();
        


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
            if (listOfFiles[i].isFile()) {
                vstacks.add(IJ.openVirtual(listOfFiles[i].getPath()));
                Integer current_stack_size = vstacks.get(i).getStackSize();
                total_size += current_stack_size;
                slice_intervals.add(current_stack_size);
                System.out.print(Integer.toString(i) + ", " + listOfFiles[i].getPath() + ", " + Integer.toString(current_stack_size) + "\n");
            } else {
                IJ.error("Error: File is not file.");
            }
        }

        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }

        System.gc();
        return DOES_8G + DOES_16 + DOES_32 + NO_IMAGE_REQUIRED;
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

        stack = vstacks.get(0);


        ImagePlus img = new ImagePlus("", stack.getStack().getProcessor(1));
        img.show();


        CLIJ2 clij2 = CLIJ2.getInstance();

        // conversion
        ClearCLBuffer input = clij2.push(img);
        ClearCLBuffer output = clij2.create(input);

        // blur
        float sigma = 20;
        clij2.gaussianBlur(input, output, sigma, sigma);

        ImagePlus result = clij2.pull(output);
        result.show();

        // free memory afterwards
        input.close();
        output.close();


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