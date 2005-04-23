#include "parse.h"

#include <assert.h>
#include <ctype.h>
#include <malloc.h>
#define _GNU_SOURCE
#include <string.h>

args_t arg_parse(const char* line_raw) {
    args_t self;
    int numargs = 0;
    const char *end, *last;
    /* First pass to count how many args... */
    end = line_raw;
    while(*end && isspace(*end)) ++end;
    //Skip initial space...
    for(;;) {
        while(*end && !isspace(*end)) ++end;
        //Go to end of argument
        ++numargs;
        while(*end && isspace(*end)) ++end;
        //Go to end of space after argument
        if(!*end) break;
    }
    self.num = numargs; // One more # args than spaces.
    self.arg = malloc(sizeof(arg_t)*numargs);

    /* Second pass to assign args.  (Lemee alone, is more efficient than a linked list!) */
    last = line_raw;
    numargs = 0; //Now numargs is which current arg.
    end = line_raw;
    while(*end && isspace(*end)) ++end;
    //Skip initial space...
    for(;;) {
        arg_t* nextarg = self.arg + numargs;;
        const char* isbinary;
        while(*end && !isspace(*end)) ++end;
        //Go to end of argument
        isbinary = strchr(last,'='); //Is there a value?
        
        //Make sure not to pass end in our search for =
        if(isbinary && (isbinary < end)) {
            nextarg->name = string_ncreate(last,isbinary-last);
            nextarg->value = string_ncreate(isbinary+1,end-isbinary-1);
        } else {
            nextarg->name = string_ncreate(last,end-last);
            nextarg->value = string_create(NULL);
        }
        ++numargs;
        while(*end && isspace(*end)) ++end;
        //Go to end of space after argument
        if(!*end) break;
        last = end;
    }
    return self;
}

void arg_done(args_t self) {
    free(self.arg);
    self.arg = NULL;
    self.num = 0;
}

arg_t* arg_get(args_t self, int index) {
    if(index >= self.num) return NULL;
    return self.arg + index;
}

arg_t* arg_find(args_t self,string_t testname) {
    int index;
    for(index=0;index<self.num;++index) {
        if(string_equali(self.arg[index].name,testname)) {
            return self.arg + index;
        }
    }

    return NULL;
}
