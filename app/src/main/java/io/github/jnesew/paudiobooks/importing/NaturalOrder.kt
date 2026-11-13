package io.github.jnesew.paudiobooks.importing

/** Compares digit runs without converting them to bounded integers. Stable ties preserve picker order. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]; val y = b[j]
            if (x in '0'..'9' && y in '0'..'9') {
                var ie = i; while (ie < a.length && a[ie] in '0'..'9') ie++
                var je = j; while (je < b.length && b[je] in '0'..'9') je++
                var iz = i; while (iz < ie && a[iz] == '0') iz++
                var jz = j; while (jz < je && b[jz] == '0') jz++
                val length = (ie - iz).compareTo(je - jz)
                if (length != 0) return length
                for (k in 0 until ie - iz) {
                    val c = a[iz + k].compareTo(b[jz + k]); if (c != 0) return c
                }
                i = ie; j = je
            } else {
                val c = x.lowercaseChar().compareTo(y.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
