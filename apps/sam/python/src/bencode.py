# Written by Petru Paler
# see LICENSE.txt for license information

from types import IntType, LongType, StringType, ListType, TupleType, DictType
import re
from cStringIO import StringIO

int_filter = re.compile('(0|-?[1-9][0-9]*)e')

def decode_int(x, f):
    m = int_filter.match(x, f)
    if m is None:
        raise ValueError
    return (long(m.group(1)), m.end())

string_filter = re.compile('(0|[1-9][0-9]*):')

def decode_string(x, f):
    m = string_filter.match(x, f)
    if m is None:
        raise ValueError
    l = int(m.group(1))
    s = m.end()
    return (x[s:s+l], s + l)

def decode_list(x, f):
    r = []
    while x[f] != 'e':
        v, f = bdecode_rec(x, f)
        r.append(v)
    return (r, f + 1)

def decode_dict(x, f):
    r = {}
    lastkey = None
    while x[f] != 'e':
        k, f = decode_string(x, f)
        if lastkey is not None and lastkey >= k:
            raise ValueError
        lastkey = k
        v, f = bdecode_rec(x, f)
        r[k] = v
    return (r, f + 1)

def bdecode_rec(x, f):
    t = x[f]
    if t == 'i':
        return decode_int(x, f + 1)
    elif t == 'l':
        return decode_list(x, f + 1)
    elif t == 'd':
        return decode_dict(x, f + 1)
    else:
        return decode_string(x, f)

def bdecode(x):
    try:
        r, l = bdecode_rec(x, 0)
    except IndexError:
        raise ValueError
    if l != len(x):
        raise ValueError
    return r

def test_bdecode():
    try:
        bdecode('0:0:')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('ie')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('i341foo382e')
        assert 0
    except ValueError:
        pass
    assert bdecode('i4e') == 4L
    assert bdecode('i0e') == 0L
    assert bdecode('i123456789e') == 123456789L
    assert bdecode('i-10e') == -10L
    try:
        bdecode('i-0e')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('i123')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('i6easd')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('35208734823ljdahflajhdf')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('2:abfdjslhfld')
        assert 0
    except ValueError:
        pass
    assert bdecode('0:') == ''
    assert bdecode('3:abc') == 'abc'
    assert bdecode('10:1234567890') == '1234567890'
    try:
        bdecode('02:xy')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('l')
        assert 0
    except ValueError:
        pass
    assert bdecode('le') == []
    try:
        bdecode('leanfdldjfh')
        assert 0
    except ValueError:
        pass
    assert bdecode('l0:0:0:e') == ['', '', '']
    try:
        bdecode('relwjhrlewjh')
        assert 0
    except ValueError:
        pass
    assert bdecode('li1ei2ei3ee') == [1, 2, 3]
    assert bdecode('l3:asd2:xye') == ['asd', 'xy']
    assert bdecode('ll5:Alice3:Bobeli2ei3eee') == [['Alice', 'Bob'], [2, 3]]
    try:
        bdecode('d')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('defoobar')
        assert 0
    except ValueError:
        pass
    assert bdecode('de') == {}
    assert bdecode('d3:agei25e4:eyes4:bluee') == {'age': 25, 'eyes': 'blue'}
    assert bdecode('d8:spam.mp3d6:author5:Alice6:lengthi100000eee') == {'spam.mp3': {'author': 'Alice', 'length': 100000}}
    try:
        bdecode('d3:fooe')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('di1e0:e')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('d1:b0:1:a0:e')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('d1:a0:1:a0:e')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('i03e')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('l01:ae')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('9999:x')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('l0:')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('d0:0:')
        assert 0
    except ValueError:
        pass
    try:
        bdecode('d0:')
        assert 0
    except ValueError:
        pass

def bencode_rec(x, b):
    t = type(x)
    if t in (IntType, LongType):
        b.write('i%de' % x)
    elif t is StringType:
        b.write('%d:%s' % (len(x), x))
    elif t in (ListType, TupleType):
        b.write('l')
        for e in x:
            bencode_rec(e, b)
        b.write('e')
    elif t is DictType:
        b.write('d')
        keylist = x.keys()
        keylist.sort()
        for k in keylist:
            assert type(k) is StringType
            bencode_rec(k, b)
            bencode_rec(x[k], b)
        b.write('e')
    else:
        assert 0

def bencode(x):
    b = StringIO()
    bencode_rec(x, b)
    return b.getvalue()

def test_bencode():
    assert bencode(4) == 'i4e'
    assert bencode(0) == 'i0e'
    assert bencode(-10) == 'i-10e'
    assert bencode(12345678901234567890L) == 'i12345678901234567890e'
    assert bencode('') == '0:'
    assert bencode('abc') == '3:abc'
    assert bencode('1234567890') == '10:1234567890'
    assert bencode([]) == 'le'
    assert bencode([1, 2, 3]) == 'li1ei2ei3ee'
    assert bencode([['Alice', 'Bob'], [2, 3]]) == 'll5:Alice3:Bobeli2ei3eee'
    assert bencode({}) == 'de'
    assert bencode({'age': 25, 'eyes': 'blue'}) == 'd3:agei25e4:eyes4:bluee'
    assert bencode({'spam.mp3': {'author': 'Alice', 'length': 100000}}) == 'd8:spam.mp3d6:author5:Alice6:lengthi100000eee'
    try:
        bencode({1: 'foo'})
        assert 0
    except AssertionError:
        pass

