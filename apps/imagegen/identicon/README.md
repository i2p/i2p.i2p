Identicon
=========

###License


        (The MIT License)
        
        Copyright (c) 2007-2014 Don Park <donpark@docuverse.com>
        Contributor   2014-2014 Paulo Miguel Almeida Rodenas <paulo.ubuntu@gmail.com>
        
        Permission is hereby granted, free of charge, to any person obtaining
        a copy of this software and associated documentation files (the
        'Software'), to deal in the Software without restriction, including
        without limitation the rights to use, copy, modify, merge, publish,
        distribute, sublicense, and/or sell copies of the Software, and to
        permit persons to whom the Software is furnished to do so, subject to
        the following conditions:
        
        The above copyright notice and this permission notice shall be
        included in all copies or substantial portions of the Software.
        
        THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND,
        EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
        MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
        IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
        CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
        TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
        SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
        
###Compiling and Running it

+ On root path run this:

        mvn clean install

+ To run the example webapp run this in /webappexample folder

        mvn clean compile package tomcat7:run -Ptomcat

+ Example Urls

        http://localhost:8080/9block?code=-2034984870&size=64
        http://localhost:8080/9block?code=-2034954870&size=64
        http://localhost:8080/9block?code=-2034894870&size=64

