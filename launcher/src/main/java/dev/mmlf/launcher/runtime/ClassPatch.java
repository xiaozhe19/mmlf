package dev.mmlf.launcher.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Minimal class-file edit: makes every call of one static method go to a method of the same name and descriptor in
 * another class, by pointing that method reference's class at a new constant. No instruction is touched, so offsets,
 * stack maps and lengths stay valid.
 */
final class ClassPatch {
    private ClassPatch() {}

    /** Returns {@code bytes} with {@code owner.name} calls redirected to {@code newOwner}; unchanged when absent. */
    static byte[] redirectStatic(byte[] bytes,String owner,String name,String newOwner) throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        int magic=in.readInt(); if(magic!=0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort(); in.readUnsignedShort();
        int count=in.readUnsignedShort();
        int poolStart=10;
        // Offsets and contents of the entries we need.
        int[] offsets=new int[count]; int[] tags=new int[count];
        String[] utf=new String[count]; int[] a=new int[count], b=new int[count];
        int pos=poolStart;
        for(int i=1;i<count;i++) {
            offsets[i]=pos;
            int tag=in.readUnsignedByte(); tags[i]=tag; pos++;
            switch(tag) {
                case 1 -> { int len=in.readUnsignedShort(); byte[] s=in.readNBytes(len); utf[i]=new String(s,StandardCharsets.UTF_8); pos+=2+len; }
                case 7, 8, 16, 19, 20 -> { a[i]=in.readUnsignedShort(); pos+=2; }
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); pos+=3; }
                case 9, 10, 11, 12, 17, 18 -> { a[i]=in.readUnsignedShort(); b[i]=in.readUnsignedShort(); pos+=4; }
                case 3, 4 -> { in.readInt(); pos+=4; }
                case 5, 6 -> { in.readLong(); pos+=8; i++; }
                default -> throw new IOException("unknown constant tag "+tag);
            }
        }
        int poolEnd=pos;
        int target=-1;
        for(int i=1;i<count;i++) {
            if(tags[i]!=10) continue;
            int cls=a[i], nat=b[i];
            if(tags[cls]==7 && owner.equals(utf[a[cls]]) && tags[nat]==12 && name.equals(utf[a[nat]])) { target=i; break; }
        }
        if(target<0) return bytes;
        // Append Utf8(newOwner) and Class(that Utf8); point the method reference at the new class.
        ByteArrayOutputStream extra=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(extra);
        byte[] nameBytes=newOwner.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1); out.writeShort(nameBytes.length); out.write(nameBytes);
        out.writeByte(7); out.writeShort(count);
        int newClass=count+1;
        byte[] patched=new byte[bytes.length+extra.size()];
        System.arraycopy(bytes,0,patched,0,poolEnd);
        System.arraycopy(extra.toByteArray(),0,patched,poolEnd,extra.size());
        System.arraycopy(bytes,poolEnd,patched,poolEnd+extra.size(),bytes.length-poolEnd);
        int newCount=count+2;
        patched[8]=(byte)(newCount>>8); patched[9]=(byte)newCount;
        int ref=offsets[target]+1;
        patched[ref]=(byte)(newClass>>8); patched[ref+1]=(byte)newClass;
        return patched;
    }
}
