package com.wurgobes.imagej;

import static java.lang.Double.compare;
import java.util.*;
import static org.apache.commons.lang3.ArrayUtils.swap;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.List;

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
    
    public static float[] ShortToFloat(short[] array) {
        float[] int_arr = new float[array.length];
        for(int i = 0; i < array.length; i++){
            int_arr[i] = (float) array[i];
        }
        return int_arr;
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
    /**
     * Returns median of list in linear time.
     * 
     * @param list list to search, which may be reordered on return
     * @return median of array in linear time.
     */
    public static double getMedian(Integer[] arr) {
        double[] list = new double[arr.length];
        for(int i=0; i<arr.length; i++) {
            list[i] = arr[i];
        }
        
        int s = list.length;
        if (s < 1)
            throw new IllegalArgumentException();
        int pos = select(list, 0, s, s / 2);
        return list[pos];
    }
    
    public static double getMedian(short[] arr) {
        double[] list = new double[arr.length];
        for(int i=0; i<arr.length; i++) {
            list[i] = arr[i];
        }
        int s = list.length;
        if (s < 1)
            throw new IllegalArgumentException();
        int pos = select(list, 0, s, s / 2);
        return list[pos];
    }
    
    public static double getMedian(Byte[] arr) {
        double[] list = new double[arr.length];
        for(int i=0; i<arr.length; i++) {
            list[i] = arr[i];
        }
        int s = list.length;
        if (s < 1)
            throw new IllegalArgumentException();
        int pos = select(list, 0, s, s / 2);
        return list[pos];
    }

    /**
    * Returns position of k'th largest element of sub-list.
    * 
    * @param list list to search, whose sub-list may be shuffled before
    *            returning
    * @param lo first element of sub-list in list
    * @param hi just after last element of sub-list in list
    * @param k
    * @return position of k'th largest element of (possibly shuffled) sub-list.
    */
    static int select(double[] list, int lo, int hi, int k) {
        int n = hi - lo;
        if (n < 2)
            return lo;

        double pivot = list[lo + (k * 7919) % n]; // Pick a random pivot

        // Triage list to [<pivot][=pivot][>pivot]
        int nLess = 0, nSame = 0, nMore = 0;
        int lo3 = lo;
        int hi3 = hi;
        while (lo3 < hi3) {
            double e = list[lo3];
            int cmp = compare(e, pivot);
            if (cmp < 0) {
                nLess++;
                lo3++;
            } else if (cmp > 0) {
                swap(list, lo3, --hi3);
                if (nSame > 0)
                    swap(list, hi3, hi3 + nSame);
                nMore++;
            } else {
                nSame++;
                swap(list, lo3, --hi3);
            }
        }
        assert (nSame > 0);
        assert (nLess + nSame + nMore == n);
        assert (list[lo + nLess] == pivot);
        assert (list[hi - nMore - 1] == pivot);
        if (k >= n - nMore)
            return select(list, hi - nMore, hi, k - nLess - nSame);
        else if (k < nLess)
            return select(list, lo, lo + nLess, k);
        return lo + k;
    }

    /**
     * Partition sub-list into 3 parts [<pivot][pivot][>pivot].
     * 
     * @param list
     * @param lo
     * @param hi
     * @param pos input position of pivot value
     * @return output position of pivot value
     */
    private static int triage(ArrayList<Comparable> list, int lo, int hi,
            int pos) {
        Comparable pivot = list.get(pos);
        int lo3 = lo;
        int hi3 = hi;
        while (lo3 < hi3) {
            Comparable e = list.get(lo3);
            int cmp = e.compareTo(pivot);
            if (cmp < 0)
                lo3++;
            else if (cmp > 0)
                Collections.swap(list, lo3, --hi3);
            else {
                while (hi3 > lo3 + 1) {
                    assert (list.get(lo3).compareTo(pivot) == 0);
                    e = list.get(--hi3);
                    cmp = e.compareTo(pivot);
                    if (cmp <= 0) {
                        if (lo3 + 1 == hi3) {
                            Collections.swap(list, lo3, lo3 + 1);
                            lo3++;
                            break;
                        }
                        Collections.swap(list, lo3, lo3 + 1);
                        assert (list.get(lo3 + 1).compareTo(pivot) == 0);
                        Collections.swap(list, lo3, hi3);
                        lo3++;
                        hi3++;
                    }
                }
                break;
            }
        }
        assert (list.get(lo3).compareTo(pivot) == 0);
        return lo3;
    }

}