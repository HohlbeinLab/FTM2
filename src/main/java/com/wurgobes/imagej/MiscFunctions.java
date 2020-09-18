package com.wurgobes.imagej;

import static org.apache.commons.lang3.ArrayUtils.swap;

import java.util.Arrays;

import org.apache.commons.math3.stat.descriptive.rank.Median;

//https://stackoverflow.com/questions/1790360/median-of-medians-in-java
public class MiscFunctions {
    private MiscFunctions() {

    }

    
    public static int[] ShortToInt(short[] array) {
        int[] int_arr = new int[array.length];
        for(int i = 0; i < array.length; i++){
            int_arr[i] = (int) array[i];
        }
        return int_arr;
    }
    
    public static double[] ShortToDouble(short[] array) {
        double[] double_arr = new double[array.length];
        for(int i = 0; i < array.length; i++){
            double_arr[i] = (double) array[i];
        }
        return double_arr;
    }
    
    public static int SumInt(int[] array) {
        return Arrays.stream(array).sum();
    }
    
    public static int SumShort(short[] array) {
        int sum = 0;
        for(short b : array)
          sum += b;
        return sum;
    }
    
    public static int SumByte(byte[] array) {
        int sum = 0;
        for(byte b : array)
          sum += b;
        return sum;
    }
    public static int getMedian(short[] arr){
        Arrays.sort(arr);
        return arr[arr.length/2];
    }


}