package net.i2p.util

import org.scalatest.FunSpec
import org.scalatest.Matchers


class VersionComparatorSpec extends FunSpec with Matchers {
  
    private val vc = new VersionComparator
    
    private def comp(desc : String, A : String, B : String, result : Int) = {
        it("should find that " + A +" " + desc +" " + B) {
            vc.compare(A,B) should equal (result)
        }
    }
    
    private def less(A : String, B : String) = 
      comp("is less than", A, B, -1)
    
    private def more(A : String, B : String) =
      comp("is more than", A, B, 1)
      
    private def same(A : String, B : String) =
      comp("equals", A, B, 0)
    
    describe("A VersionComparator") {
        same("0.1.2","0.1.2")

        less("0.1.2","0.1.3")
        more("0.1.3","0.1.2")

        more("0.1.2.3.4", "0.1.2")
        less("0.1.2", "0.1.2.3.4")

        more("0.1.3", "0.1.2.3.4")
	less("0.1.2.3.4", "0.1.3")

        same("0.1.2","0-1-2")
	same("0-1-2","0.1.2")

        same("0.1.2","0_1_2")
	same("0_1_2","0.1.2")

        same("0.1.2-foo", "0.1.2-bar")
	same("0.1.2-bar", "0.1.2-foo")

        same("0.1-asdf3","0_1.3fdsa")
	same("0_1.3fdsa","0.1-asdf3")

        less("0.1.2","0.1.2.0") 
        more("0.1.2.0","0.1.2")
        
        /*********
        I think everything below this line should be invalid --zab.
        *********/
        less("",".")
        more(".","")

        less("-0.1.2", "-0.1.3")
        more("-0.1.3", "-0.1.2")
 
        more("0..2", "0.1.2") 
        less("0.1.2", "0..2") 

        same("asdf","fdsa")
        same("fdsa","asdf")

        same("---","___")
        same("___","---")

        same("1.2.3","0001.0002.0003")
        same("0001.0002.0003","1.2.3")

        more("as123$Aw4423-234","asdfq45#11--_")
        less("asdfq45#11--_","as123$Aw4423-234")
       
        // non-ascii string
        val byteArray = new Array[Byte](10)
        byteArray(5) = 1
        byteArray(6) = 10
        val nonAscii = new String(byteArray)
        more(nonAscii,"")
        less("",nonAscii)
        
        // huge value, can't fit in a long
        val huge = String.valueOf(Long.MaxValue)+"0000.0";
        less(huge,"1.2")
        more("1.2",huge)
    }
}
