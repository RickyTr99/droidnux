#pragma once

#include <string>

namespace droidnux::display {

// Checks the GPU buffer path the presentation core is built on and returns a
// human-readable report.
std::string probe();

}  // namespace droidnux::display
