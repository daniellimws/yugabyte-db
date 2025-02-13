// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#ifndef YB_COMMON_COMMON_TYPES_UTIL_H
#define YB_COMMON_COMMON_TYPES_UTIL_H

#include "yb/common/common_types.pb.h"

namespace yb {

static constexpr const char* kDBTypeNameUnknown = "unknown";
static constexpr const char* kDBTypeNameCql = "ycql";
static constexpr const char* kDBTypeNamePgsql = "ysql";
static constexpr const char* kDBTypeNameRedis = "yedis";

// Returns the string name of a db type.
const char* DatabaseTypeName(YQLDatabase db);

// Returns the db type from its string name.
YQLDatabase DatabaseTypeByName(const std::string& db_type_name);

} // namespace yb

#endif  // YB_COMMON_COMMON_TYPES_UTIL_H
