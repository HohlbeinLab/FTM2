package com.wurgobes.imagej;


import ij.*;
import fiji.util.gui.GenericDialogPlus;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.*;
import ij.process.ImageProcessor;

import org.scijava.command.Command;

import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;

import java.awt.image.ColorModel;
import net.imagej.ops.Op;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import io.scif.services.DatasetIOService;
import org.scijava.ui.UIService;
import java.io.File;
import java.io.IOException;
import ij.io.Opener;
import ij.io.OpenDialog;
import java.util.logging.Level;
import java.util.logging.Logger;



@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2")
public class FTM2<T> implements PlugInFilter {
    
        @Parameter
	private ImagePlus image;
                
        @Parameter
        private ImagePlus stack;
        
        @Parameter
        private ImagePlus vstack;
        
        @Parameter
	public static String sourceDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release

        @Parameter
	public static String outputDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release
        
        @Parameter
	public static String refimg="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\refstacksmall.tif";//Change before release
        
        @Parameter
        private String source_dir;
        
        @Parameter
        private String target_dir;
        
        @Parameter
        private String reference_image;
        
        @Parameter
        private Integer[] slice_intervals;
        
        @Parameter
        private Integer total_size = 0;
        
        @Parameter
        private int stack_height, stack_width, bitDepth;
        
	@Override
	public int setup(String arg, ImagePlus imp) {
                //TODO
                //Handle closure of the file select more graciously
                //Add an about
                //Hyperstack support?
                //NOTE that the final implementation probably wont have the entire stack loaded into memory as a Imagestack, at most as a virtualstack
		if (arg.equals("about")) {
                    IJ.showMessage("TODO ABOUT");
                    return DONE;
		}
                
                GenericDialogPlus gd = new GenericDialogPlus("Register Virtual Stack");

		gd.addDirectoryField("Source directory", sourceDirectory, 50);
		gd.addDirectoryField("Output directory", outputDirectory, 50);
                gd.addFileField("Reference Image", refimg, 50);
                gd.showDialog();
		
		// Exit when canceled
		if (gd.wasCanceled()) 
			return DONE;
                
                sourceDirectory = gd.getNextString();
		outputDirectory = gd.getNextString();
                refimg = gd.getNextString();

                source_dir = sourceDirectory;
                target_dir = outputDirectory;
                reference_image = refimg;
                
		if (null == source_dir) {
                    IJ.error("Error: No source directory was provided.");
                    return DONE;
		}
		if (null == target_dir) {
                    IJ.error("Error: No output directory was provided.");
                    return DONE;
		}
                if (null == reference_image) {
                    IJ.error("Error: No reference image was provided. Take a single frame from the stack and put it in a seperate location");
                    return DONE;
		}
		
                source_dir = source_dir.replace('\\', '/');
		if (!source_dir.endsWith("/")) source_dir += "/";
                
		target_dir = target_dir.replace('\\', '/');
		if (!target_dir.endsWith("/")) target_dir += "/";
                
                reference_image = reference_image.replace('\\', '/');

                
		if( (new File( source_dir )).exists() == false ){
                    IJ.error("Error: source directory " + source_dir + " does not exist.");
                    return DONE;
		}
                if( (new File( reference_image )).exists() == false ){
                    IJ.error("Error: reference image " + reference_image + " does not exist.");
                    return DONE;
		}
		
                if ((new File( target_dir )).exists() == false){
                    if(!new File(target_dir).mkdir()) {
                        IJ.error("Error: Failed to create target directory " + target_dir);
                    }
                }
                
		ImagePlus ref = IJ.openImage(reference_image);
                //ImagePlus img = FolderOpener.open(source_dir, "virtual"); //doesnt open as virtual stack, funny huh
                stack_height = ref.getHeight();
                stack_width = ref.getWidth();
                bitDepth = ref.getBitDepth();
                
                ColorModel cm = ref.getProcessor().getColorModel();
                
                File folder = new File(source_dir);
                File[] listOfFiles = folder.listFiles();
                ImagePlus[] vstacks = new ImagePlus[listOfFiles.length];
                
                
                for (int i = 0; i < listOfFiles.length; i++) {                       
                    if (listOfFiles[i].isFile()) {
                        vstacks[i] = IJ.openVirtual(listOfFiles[i].getPath());
                        
                        System.out.print(Integer.toString(i) + ", " + listOfFiles[i].getPath() + ", " + Integer.toString(vstacks[i].getStackSize()) + "\n");
                    } else {
                        IJ.error("Error: File is not file.");
                    }
                }
                
                stack = vstacks[0];

                if(stack.getStackSize() == 1){
                    IJ.error("Error: Stack must have size larger than 1.");
                }
                                                            
		return DOES_8G + DOES_16 + DOES_32 + NO_IMAGE_REQUIRED;
	}

	@Override
	public void run(ImageProcessor ip) {
                //This will first be getting a initial implementation to ensure it works
                //If not yet implemented then, it will be ensured files>20GB will work without being loaded into memory
                
                ImagePlus img = new Duplicator().run(stack, 1, 1);         
                img.show();
                
                CLIJ2 clij2 = CLIJ2.getInstance();

                // conversion
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
                
                String dir = null;
                //dir = "C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images";
		//ImagePlus image = IJ.openImage("C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\stack_small.tif");
		//image.show();

		IJ.runPlugIn(FTM2.class.getName(), dir);
	}
}