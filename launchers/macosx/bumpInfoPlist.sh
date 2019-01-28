#!/usr/bin/env ruby
git = `sh /etc/profile; which git`.chomp
app_build = `#{git} rev-list HEAD --count`.chomp.to_i
`/usr/libexec/PlistBuddy -c "Set :CFBundleVersion #{app_build}" "${TARGET_BUILD_DIR}/${INFOPLIST_PATH}"`
puts "Updated #{ENV['TARGET_BUILD_DIR']}/#{ENV['INFOPLIST_PATH']}"

