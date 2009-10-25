#!/usr/bin/env ruby1.9

require 'date'

MAPPING={
  '"zzz@mail.i2p"' => 'zzz',
  '"z3d@mail.i2p"' => 'dr|z3d',
  '"sponge@mail.i2p"' => 'sponge',
  '"mkvore-commit@mail.i2p"' => 'mkvore',
  '"complication@mail.i2p"' => 'complication',
  '"dev@welterde.de"' => 'welterde',
  '"Oldaris@mail.i2p"' => 'oldaris',
  '"mathiasdm@mail.i2p"' => 'mathiasdm',
  '"amiga4000@mail.i2p"' => 'amiga4000',
  '"dream@mail.i2p"' => 'dream',
  '"walking@mail.i2p"' => 'walking',
  '"jrandom@i2p.net"' => 'jrandom',
  '"jrandom-transfer@i2p.net"' => 'jrandom',
  '"transport@welterde.de"' => 'welterde',
  '"echelon@mail.i2p"' => 'eche|on',
  '"z3d@i2p"' => 'dr|z3d',
  '"cervantes@mail.i2p"' => 'cervantes',
  '"BlubMail@mail.i2p"' => 'BlubMail'
}

roots=[]
`mtn automate roots`.split("\n").map {|n| n.strip}.each do |rev|
  roots << rev
end

revs = []
roots.each do |root|
  `mtn automate descendents #{root}`.split("\n").map {|n| n.strip}.each do |rev|
    revs << rev
  end
end

# open the file
f=File::open('/tmp/i2p.xml', 'w')
f << '<?xml version="1.0"?>'
f << "\n"
f << '<file_events>'
f << "\n"
d=[]
revs.each do |rev|
  print rev
  print " - "
  certs_=`mtn automate certs #{rev}`.split("\n").map{|l|l2=l.strip; l2.split(" ", 2) if l2 != ""}
  author=certs_[3][1]
  date=nil
  branch='""'
  certs_.each_index do |i|
    next unless certs_[i]
    if certs_[i][1]
      date=certs_[i+1][1] if certs_[i][1].strip === '"date"'
      branch=certs_[i+1][1] if certs_[i][1].strip === '"branch"'
    end
  end
  info=`mtn automate get_revision #{rev}`.strip.split("\n").map{|l|l2=l.strip; l2.split(" ", 2) if l2 != ""}
  print date
  date=DateTime.parse(date).to_time.to_i*1000
  print " - "
  print date
  print " - "
  print branch
  print " - "
  puts author
  info.each do |line|
    next unless line
    d << [date, (branch.strip[1..-2] + '//' + line[1].strip[1..-2]), (MAPPING[author] or author[1..-2])] if line[0].strip == "patch"
  end
end

d.sort! {|a,b| a[0] <=> b[0]}

d.each do |a|
  f << "<event date=\"#{a[0]}\" filename=\"#{a[1]}\" author=\"#{a[2]}\" />\n"
end

f << '</file_events>'
f << "\n"
f.close()
