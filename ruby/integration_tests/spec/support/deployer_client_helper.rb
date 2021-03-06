# Copyright 2015 VMware, Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, without warranties or
# conditions of any kind, EITHER EXPRESS OR IMPLIED. See the License for the
# specific language governing permissions and limitations under the License.

require_relative "../support/deployer/deployer_client"

class DeployerClientHelper
  def self.deployer
    setup_client((ENV["DEPLOYER_PORT"] || "18000").strip)
  end

  def self.dcp
    setup_client((ENV["DEPLOYER_DCP_PORT"] || "18001").strip)
  end

  private

  def self.setup_client(port)
    address = (ENV["DEPLOYER_ADDRESS"] || "localhost").strip
    deployer_address = "#{address}:#{port}"
    deployer_address = "http://#{deployer_address}" unless deployer_address.start_with?("http")

    EsxCloud::DeployerClient.new(deployer_address)
  end
end
