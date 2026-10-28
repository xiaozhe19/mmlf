package dev.mmlf.common;
import java.util.*;
public record RemoteItemRef(String owner,UUID token) {
 public RemoteItemRef { if(owner==null || !owner.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid item owner");Objects.requireNonNull(token); }
 public String encode() {return "mmlf@"+owner+":"+token;}
 public static Optional<RemoteItemRef> parse(String text) {
  if(text==null || !text.startsWith("mmlf@")) return Optional.empty();
  int split=text.indexOf(':',5);if(split<0) throw new IllegalArgumentException("Invalid remote item reference");
  return Optional.of(new RemoteItemRef(text.substring(5,split),UUID.fromString(text.substring(split+1))));
 }
}
