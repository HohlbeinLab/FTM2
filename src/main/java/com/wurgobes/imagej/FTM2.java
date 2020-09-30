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
import net.imagej.Dataset;
import net.imagej.ImgPlus;
import net.imagej.ops.transform.crop.CropImgPlus;
import net.imglib2.*;
import net.imglib2.RandomAccess;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.DiskCachedCellImgFactory;
import net.imglib2.cache.img.DiskCachedCellImgOptions;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.VirtualStackAdapter;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.ops.parse.token.Real;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.scijava.plugin.*;
import org.scijava.Priority;
import org.scijava.command.Command;

import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;

import io.scif.img.IO;
import io.scif.img.ImgIOException;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.*; 
import java.awt.image.ColorModel;

import jdk.nashorn.internal.ir.debug.ObjectSizeCalculator;

import javax.swing.*;
import java.awt.event.ActionListener;
import ij.gui.YesNoCancelDialog;



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


@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2", label="FTM2", priority = Priority.VERY_HIGH)
//public class FTM2 implements ExtendedPlugInFilter {
public class FTM2< T extends RealType< T >>  implements ExtendedPlugInFilter, Command {


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

    private Img<T> imageData;
    private Dataset currentData;

    boolean force_gpu = false;


    public boolean saveImagePlus(final String path, ImagePlus impP){
        try {
                return new FileSaver(impP).saveAsTiff(path);
        } catch (Exception e) {
                return false;
        }
    }

    

    @Override
    public int setup(String arg, ImagePlus imp) {
        //TODO
        //Handle closure of the file select more graciously
        //Add an about
        //NOTE that the final implementation probably wont have the entire stack loaded into memory as a Imagestack, at most as a virtualstack

        boolean pre_loaded_image = imp != null;

        String sourceDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release
        String outputDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release
        MultiFileSelect fs = new MultiFileSelect();
        File[] selected_files = null;

        GenericDialogPlus gd = new GenericDialogPlus("Settings");

        gd.addMessage("Temporal Median Filter");
        gd.addDirectoryField("Source directory", sourceDirectory, 50);
        gd.addButton("Select Files", fs);
        gd.addToSameRow();
        gd.addButton("Clear Selected Files", fs);
        gd.addDirectoryField("Output directory", outputDirectory, 50);
        gd.addNumericField("Window size", window, 0);
        gd.addNumericField("Begin", start, 0);
        gd.addNumericField("End (0 for all)", end, 0);
        gd.addCheckbox("Use open image?", pre_loaded_image);
        gd.addCheckbox("Force GPU?", force_gpu);


        gd.showDialog();

        // Exit when canceled
        if (gd.wasCanceled())
            return DONE;



        sourceDirectory = gd.getNextString();
        selected_files =  fs.getFiles();
        outputDirectory = gd.getNextString();
        window = (int)gd.getNextNumber();
        start = (int)gd.getNextNumber();
        end = (int)gd.getNextNumber();
        pre_loaded_image = gd.getNextBoolean();
        force_gpu = gd.getNextBoolean();



        //@Parameter
        String source_dir = sourceDirectory;
        target_dir = outputDirectory;


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
                dimension = slice_width * slice_height; //Amount of pixels per image
                bit_depth = vstacks.get(0).getBitDepth(); // bitdepth

            }
        } else {
            int current_stack_size = 0;
            if(force_gpu){

                assert imp != null;
                vstacks.add(imp.getStack());
                current_stack_size = vstacks.get(0).size();
                slice_intervals.add(current_stack_size + total_size);
                total_size += current_stack_size;

                slice_height = vstacks.get(0).getHeight();
                slice_width = vstacks.get(0).getWidth();
                dimension = slice_width * slice_height; //Amount of pixels per image
                bit_depth = vstacks.get(0).getBitDepth(); // bitdepth
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

        if(bit_depth == 32) IJ.error("currently does not support 32 bit");

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


            final VirtualStack final_virtual_stack = new VirtualStack(slice_width, slice_height, null, target_dir);
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

            @SuppressWarnings("unchecked")
            RandomAccessibleInterval< T > data = (RandomAccessibleInterval<T>) Views.offsetInterval(imageData, new long[] {0, 0, start - 1}, new long[] {imageData.dimension(0), imageData.dimension(1) , end});

            TemporalMedian.main(data, window);
            ImageJFunctions.show(data);
        }


        //Have to run this since otherwise the data will not be visible (does not change the data)
        IJ.run("Enhance Contrast", "saturated=0.0");


        long stopTime = System.nanoTime() - startTime;
        double spendTime = (double)stopTime/1000000000;
        System.out.println("Script took " + String.format("%.3f",spendTime) + " s");
        System.out.println("Processed " + (end - start + 1) + " frames at " +  String.format("%.1f", (double)(total_disk_size/(1024*1024)/spendTime))+ " MB/s");

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

        final ImageJ IJ_Instance = new ImageJ();
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