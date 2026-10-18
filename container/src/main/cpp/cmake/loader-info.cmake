# Writes loader-info.c: the offset of pokedata_workaround from _start in the
# proot loader. Replaces the readelf + awk step of proot's GNUmakefile, so the
# build also works on Windows hosts.
#
# Inputs: READELF, LOADER, OUTPUT

execute_process(
    COMMAND ${READELF} -sW ${LOADER}
    OUTPUT_VARIABLE symbols
    RESULT_VARIABLE result)
if(NOT result EQUAL 0)
    message(FATAL_ERROR "readelf failed on ${LOADER}")
endif()

foreach(name IN ITEMS _start pokedata_workaround)
    string(REGEX MATCH "[0-9]+: ([0-9a-fA-F]+) +[0-9]+ +[A-Z]+ +[A-Z]+ +[A-Z]+ +[0-9A-Z]+ ${name}\n" match "${symbols}")
    if(NOT match)
        message(FATAL_ERROR "symbol ${name} not found in ${LOADER}")
    endif()
    set(addr_${name} "0x${CMAKE_MATCH_1}")
endforeach()

math(EXPR offset "${addr_pokedata_workaround} - ${addr__start}")
file(WRITE ${OUTPUT}
"#include <unistd.h>
const ssize_t offset_to_pokedata_workaround = ${offset};
")
