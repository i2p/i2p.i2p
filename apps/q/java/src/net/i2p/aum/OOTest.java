package net.i2p.aum;


public class OOTest
{
    public int add(int a, int b)
    {
      return (a + b);
    }
    
    public static void main(String[] args)
    {
        OOTest mytest = new OOTest();
        System.out.println(mytest.add(3,3));
    }
}


