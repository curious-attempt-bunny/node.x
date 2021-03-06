# Copyright 2011 VMware, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

require "nodex"
include Nodex

Nodex::go {
  conns = SharedData::get_set("conns")
  NetServer.new.connect_handler { |socket|
    conns.add(socket.write_handler_id)
    socket.data_handler { |data|
      conns.each { |actor_id| Nodex::send_to_handler(actor_id, data) }
    }
    socket.closed_handler { conns.delete(socket.write_handler_id) }
  }.listen(8080)
}

puts "hit enter to exit"
STDIN.gets


